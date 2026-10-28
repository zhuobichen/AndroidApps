import { createServer } from 'node:http';
import { createReadStream, createWriteStream, mkdirSync, readFileSync, writeFileSync, renameSync, existsSync, statSync, unlinkSync, readdirSync } from 'node:fs';
import { readdir } from 'node:fs/promises';
import { randomBytes, timingSafeEqual, randomUUID } from 'node:crypto';
import { pipeline } from 'node:stream/promises';
import { Transform } from 'node:stream';
import { basename, extname, join, resolve } from 'node:path';
import { networkInterfaces } from 'node:os';
import { fileURLToPath } from 'node:url';

const here = fileURLToPath(new URL('.', import.meta.url));
const dataRoot = resolve(here, 'data');
const jobsRoot = join(dataRoot, 'jobs');
const tokenPath = join(dataRoot, 'token.txt');
const port = Number(process.env.VIDEOQUIZ_BRIDGE_PORT || 8765);
const wlan = networkInterfaces().WLAN || [];
const bindHost = process.env.VIDEOQUIZ_BIND_HOST || wlan.find(entry => entry.family === 'IPv4' && !entry.internal)?.address || '0.0.0.0';
const maxBytes = Number(process.env.VIDEOQUIZ_MAX_BYTES || 2 * 1024 * 1024 * 1024);
const supervisorUrl = process.env.VIDEOQUIZ_SUPERVISOR_URL || 'http://127.0.0.1:3080/dsh-agent-supervisor/chat';
const targetSessionId = process.env.VIDEOQUIZ_TARGET_SESSION_ID || 'session-3b1e209a-14ac-4002-a31f-4efdc4f9064c';
// 回传地址给的是电脑上的 Harness 会话，走回环最稳，也不受网卡变化影响。
const completionUrl = `http://127.0.0.1:${port}`;
mkdirSync(jobsRoot, { recursive: true });

let token = process.env.VIDEOQUIZ_BRIDGE_TOKEN;
if (!token && existsSync(tokenPath)) token = readFileSync(tokenPath, 'utf8').trim();
if (!token) {
  token = randomBytes(32).toString('base64url');
  writeFileSync(tokenPath, token, { encoding: 'utf8', mode: 0o600 });
}

function json(res, status, value) {
  const body = Buffer.from(JSON.stringify(value));
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': body.length,
    'Cache-Control': 'no-store',
    'X-Content-Type-Options': 'nosniff',
  });
  res.end(body);
}

function auth(req) {
  const got = String(req.headers.authorization || '').replace(/^Bearer\s+/i, '');
  const a = Buffer.from(got);
  const b = Buffer.from(token);
  return a.length === b.length && timingSafeEqual(a, b);
}

function jobPath(id) {
  if (!/^[0-9a-f-]{36}$/i.test(id)) return null;
  const dir = resolve(jobsRoot, id);
  return dir.startsWith(jobsRoot + '\\') || dir.startsWith(jobsRoot + '/') ? dir : null;
}

function readJson(path) {
  try { return JSON.parse(readFileSync(path, 'utf8')); } catch { return null; }
}

function listFiles(dir, done) {
  return (done.files || []).filter(f => typeof f.name === 'string' && /^[\w .()\-]+\.[A-Za-z0-9]{1,8}$/.test(f.name))
    .map(f => {
      const path = join(dir, 'results', f.name);
      if (!existsSync(path)) return null;
      const stat = statSync(path);
      return { name: f.name, mimeType: f.mimeType || 'application/octet-stream', size: stat.size };
    }).filter(Boolean);
}

// 工作区范围：会话语义上属于某个工作区（DSH 的 sessions 目录按 cwd 分组）。
// 桥接只允许列 / 投递「当前工作区」里的会话，避免手机把任务扔到别的项目去。
const sessionsRoot = process.env.VIDEOQUIZ_SESSIONS_ROOT
  || join(process.env.DSH_HOME || 'D:/github_project/ZhaoWen_GitHub维护/dsh/home', 'sessions');
const anchorSessionId = process.env.VIDEOQUIZ_ANCHOR_SESSION || 'session-3b1e209a-14ac-4002-a31f-4efdc4f9064c';
const workspacePath = process.env.VIDEOQUIZ_WORKSPACE || 'D:\\github_project\\ZhaoWen_GitHub维护';

/** 当前工作区的会话目录（以锚点会话所在的组为准）；找不到就返回 null，表示不做范围限制。 */
function workspaceSessionIds() {
  try {
    for (const group of readdirSync(sessionsRoot)) {
      const groupDir = join(sessionsRoot, group);
      if (!existsSync(join(groupDir, anchorSessionId))) continue;
      const ids = readdirSync(groupDir).filter(name => name.startsWith('session-'));
      return { group, ids };
    }
  } catch {
    // 读不到就退回「不限制」，并在返回里如实标 scope=unrestricted
  }
  return null;
}
// 会话控制：把手机的请求透传给管理插件的确定性命令路由（不经过大模型）。
const supervisorCommandUrl = supervisorUrl.replace(/\/chat\/?$/, '/command');
const supervisorKey = () => String(process.env.VIDEOQUIZ_SUPERVISOR_KEY || '').trim();

async function supervisorCommand(payload) {
  const headers = { 'Content-Type': 'application/json' };
  const key = supervisorKey();
  if (key !== '') headers['x-supervisor-key'] = key;
  const response = await fetch(supervisorCommandUrl, {
    method: 'POST',
    headers,
    body: JSON.stringify(payload),
    signal: AbortSignal.timeout(15000),
  });
  const body = await response.json().catch(() => ({}));
  return { status: response.status, body };
}

async function readJsonBody(req, limit) {
  let raw = '';
  for await (const chunk of req) {
    raw += chunk.toString('utf8');
    if (raw.length > limit) throw new Error('request too large');
  }
  return raw === '' ? {} : JSON.parse(raw);
}

function jobState(dir, job) {
  const done = readJson(join(dir, 'done.json'));
  const forwarding = readJson(join(dir, 'forwarding.json'));
  const forwarded = readJson(join(dir, 'forwarded.json'));
  const processing = existsSync(join(dir, 'processing.json'));
  return {
    done,
    processing,
    forwarded,
    forwarding,
    status: done ? 'complete' : processing ? 'processing' : forwarded ? 'forwarded' : forwarding ? 'forwarding' : job.status || 'uploaded',
  };
}

async function sendToSession(id, job) {
  const dir = jobPath(id);
  if (!dir || !existsSync(join(dir, 'job.json'))) throw new Error('job not found');
  if (existsSync(join(dir, 'forwarding.json')) || existsSync(join(dir, 'forwarded.json'))) return;

  const scope = workspaceSessionIds();
  let session = typeof job.targetSessionId === 'string' && job.targetSessionId !== '' ? job.targetSessionId : targetSessionId;
  // 只在本工作区内投递：目标越界就退回默认会话，并在记录里写明原因
  let fallbackNote;
  if (scope !== null && !scope.ids.includes(session)) {
    fallbackNote = `requested ${session} is outside workspace ${scope.group}`;
    session = scope.ids.includes(targetSessionId) ? targetSessionId : scope.ids[0];
  }
  if (!session) throw new Error('no session available in workspace');
  writeFileSync(join(dir, 'forwarding.json'), JSON.stringify({ startedAt: new Date().toISOString(), targetSessionId: session }), 'utf8');

  try {
    const videoPath = join(dir, job.storedName);
    const resultsPath = join(dir, 'results');
    const text = [
      `VideoQuiz 上传任务 ${id} 已完整保存到本机。`,
      `视频绝对路径：${videoPath}`,
      `结果目录：${resultsPath}`,
      `回传服务地址：${completionUrl}`,
      `本机桥接辅助脚本：${join(here, 'task.ps1')}`,
      `任务要求：${job.task}`,
      `请先运行本机 pwsh 的 task.ps1：Operation=claim、BaseUrl=${completionUrl}、Id=${id}、IncludeVideoPath，拿到路径后读取视频、完成题目，在结果目录写入 answers.pdf 与 answers.txt；完成后再次运行 task.ps1：Operation=complete、BaseUrl=${completionUrl}、Id=${id}、Text=结果摘要、FilesJson='[{"name":"answers.pdf","mimeType":"application/pdf"},{"name":"answers.txt","mimeType":"text/plain"}]'。脚本自行读取本机 token.txt，禁止读取/展示/写入对话。不要改写视频源文件。`,
    ].join('\n');

    const outcome = await supervisorCommand({ op: 'send', sessionId: session, text });
    if (outcome.status !== 200 || outcome.body.ok !== true) {
      throw new Error(outcome.body.message || outcome.body.code || `command HTTP ${outcome.status}`);
    }

    writeFileSync(join(dir, 'forwarded.json'), JSON.stringify({
      forwardedAt: new Date().toISOString(),
      targetSessionId: session,
      targetTitle: outcome.body.title,
      mode: 'command',
      ...(fallbackNote === undefined ? {} : { fallbackNote }),
    }, null, 2), 'utf8');
  } catch (error) {
    try { unlinkSync(join(dir, 'forwarding.json')); } catch {}
    writeFileSync(join(dir, 'forward-error.json'), JSON.stringify({ at: new Date().toISOString(), message: error.message }, null, 2), 'utf8');
    throw error;
  }
}
const server = createServer(async (req, res) => {
  const startedAt = Date.now();
  try {
    const url = new URL(req.url || '/', `http://${req.headers.host || 'localhost'}`);
    res.on('finish', () => {
      const from = String(req.socket.remoteAddress || '').replace(/^::ffff:/, '');
      console.log(`${new Date().toISOString()} ${from} ${req.method} ${url.pathname} -> ${res.statusCode} ${Date.now() - startedAt}ms`);
    });
    if (req.method === 'GET' && url.pathname === '/api/health') {
      return json(res, 200, { ok: true, service: 'videoquiz-bridge', maxBytes });
    }
    if (!auth(req)) return json(res, 401, { error: 'unauthorized' });

    // ── 会话控制：列会话 / 新建会话 / 往指定会话投递 ──
    if (req.method === 'GET' && url.pathname === '/api/sessions') {
      try {
        const scope = workspaceSessionIds();
        const outcome = await supervisorCommand({ op: 'list' });
        if (outcome.status === 200 && outcome.body.ok === true && scope !== null) {
          const allowed = new Set(scope.ids);
          const sessions = (Array.isArray(outcome.body.sessions) ? outcome.body.sessions : []).filter(s => allowed.has(s.sessionId));
          return json(res, 200, { ok: true, count: sessions.length, workspace: scope.group, scope: 'workspace', sessions });
        }
        return json(res, outcome.status === undefined ? 200 : outcome.status, { ...outcome.body, scope: 'unrestricted' });
      } catch (error) {
        return json(res, 502, {
          ok: false,
          code: 'SUPERVISOR_UNREACHABLE',
          message: '连不上管理插件的 /command：' + error.message,
          hint: '确认 DSH 已重启并加载了新版 dsh-agent-supervisor（需要 POST /command 路由）。',
        });
      }
    }

    if (req.method === 'POST' && (url.pathname === '/api/sessions/start' || url.pathname === '/api/sessions/send')) {
      let payload;
      try {
        payload = await readJsonBody(req, 32 * 1024);
      } catch (error) {
        return json(res, 400, { ok: false, code: 'BAD_JSON', message: error.message });
      }
      const op = url.pathname.endsWith('/start') ? 'start' : 'send';
      if (op === 'send') {
        const scope = workspaceSessionIds();
        const sid = typeof payload.sessionId === 'string' ? payload.sessionId : '';
        if (scope !== null && sid !== '' && !scope.ids.includes(sid)) {
          return json(res, 403, {
            ok: false,
            code: 'OUT_OF_WORKSPACE',
            message: '这个会话不在当前工作区（' + scope.group + '）里，桥接不往其它项目投递。',
            workspace: scope.group,
          });
        }
      }
      try {
        const outcome = await supervisorCommand({ ...payload, op: op === 'start' ? 'start' : 'send', ...(op === 'start' ? { cwd: workspacePath } : {}) });
        return json(res, outcome.status === undefined ? 200 : outcome.status, outcome.body);
      } catch (error) {
        return json(res, 502, {
          ok: false,
          code: 'SUPERVISOR_UNREACHABLE',
          message: '连不上管理插件的 /command：' + error.message,
          hint: '确认 DSH 已重启并加载了新版 dsh-agent-supervisor（需要 POST /command 路由）。',
        });
      }
    }
    if (req.method === 'POST' && url.pathname === '/api/jobs') {
      const length = Number(req.headers['content-length']);
      if (!Number.isSafeInteger(length) || length <= 0) return json(res, 411, { error: 'Content-Length is required' });
      if (length > maxBytes) return json(res, 413, { error: `video exceeds ${maxBytes} bytes` });
      const rawName = req.headers['x-video-name-b64']
        ? Buffer.from(String(req.headers['x-video-name-b64']), 'base64').toString('utf8')
        : String(req.headers['x-video-name'] || 'video.mp4');
      const requestedName = basename(rawName.replaceAll('\\', '/')).replace(/[^\p{L}\p{N}._() -]/gu, '_').slice(0, 120) || 'video.mp4';
      const ext = extname(requestedName).toLowerCase();
      const allowed = new Set(['.mp4', '.mov', '.m4v', '.mkv', '.webm', '.avi', '.3gp', '.mpeg', '.mpg']);
      if (!allowed.has(ext)) return json(res, 415, { error: 'unsupported video extension' });
      const id = randomUUID();
      const dir = join(jobsRoot, id);
      mkdirSync(dir, { recursive: true });
      const tmp = join(dir, 'video.uploading');
      const storedName = `video${ext}`;
      let received = 0;
      const limiter = new Transform({
        transform(chunk, encoding, callback) {
          received += chunk.length;
          if (received > maxBytes) callback(new Error('video exceeds configured limit'));
          else callback(null, chunk);
        },
      });
      try {
        await pipeline(req, limiter, createWriteStream(tmp, { flags: 'wx' }));
        if (received !== length) throw new Error(`length mismatch: expected ${length}, received ${received}`);
        renameSync(tmp, join(dir, storedName));
        mkdirSync(join(dir, 'results'), { recursive: true });
        const wantedSession = String(req.headers['x-videoquiz-session-b64']
          ? Buffer.from(String(req.headers['x-videoquiz-session-b64']), 'base64').toString('utf8')
          : (req.headers['x-videoquiz-session'] || '')).trim();
        const task = {
          id,
          originalName: requestedName,
          storedName,
          size: received,
          contentType: String(req.headers['content-type'] || 'application/octet-stream'),
          ...(wantedSession === '' ? {} : { targetSessionId: wantedSession }),
          task: String(req.headers['x-videoquiz-task-b64']
            ? Buffer.from(String(req.headers['x-videoquiz-task-b64']), 'base64').toString('utf8')
            : (req.headers['x-videoquiz-task'] || '转录视频中的试题，保留公式与题号；生成试卷 PDF 和答案 PDF，并说明识别不确定处。')).slice(0, 2000),
          createdAt: new Date().toISOString(),
          status: 'uploaded',
        };
        writeFileSync(join(dir, 'job.json'), JSON.stringify(task, null, 2), 'utf8');
        let forwarded = false;
        try {
          await sendToSession(id, task);
          forwarded = true;
        } catch (error) {
          console.warn(`job ${id} uploaded but management-session notification failed: ${error.message}`);
        }
        return json(res, 201, { id, size: received, status: forwarded ? 'forwarded' : 'notification_failed' });
      } catch (error) {
        try { if (existsSync(tmp)) unlinkSync(tmp); } catch {}
        try { if (existsSync(dir)) {
          for (const name of ['job.json', storedName, 'video.uploading']) {
            const path = join(dir, name);
            if (existsSync(path)) unlinkSync(path);
          }
        }
        } catch {}
        return json(res, 400, { error: `upload failed: ${error.message}` });
      }
    }

    const retryForward = url.pathname.match(/^\/api\/jobs\/([0-9a-f-]{36})\/forward$/i);
    if (req.method === 'POST' && retryForward) {
      const id = retryForward[1];
      const dir = jobPath(id);
      const job = dir ? readJson(join(dir, 'job.json')) : null;
      if (!job) return json(res, 404, { error: 'job not found' });
      if (existsSync(join(dir, 'done.json'))) return json(res, 409, { error: 'job already completed' });
      try {
        await sendToSession(id, job);
        return json(res, 200, { ok: true, id, status: 'forwarded' });
      } catch (error) {
        return json(res, 502, { ok: false, id, status: 'notification_failed', error: error.message });
      }
    }

    if (req.method === 'POST' && url.pathname === '/api/jobs/claim') {
      let body = '';
      for await (const chunk of req) {
        body += chunk.toString('utf8');
        if (body.length > 4096) return json(res, 413, { error: 'request too large' });
      }
      const payload = JSON.parse(body || '{}');
      const id = String(payload.id || '');
      const dir = jobPath(id);
      if (!dir || !existsSync(join(dir, 'job.json'))) return json(res, 404, { error: 'job not found' });
      if (existsSync(join(dir, 'done.json'))) return json(res, 409, { error: 'job already completed' });
      writeFileSync(join(dir, 'processing.json'), JSON.stringify({ startedAt: new Date().toISOString() }), 'utf8');
      const job = readJson(join(dir, 'job.json'));
      return json(res, 200, { ...job, videoPath: join(dir, job.storedName), resultsPath: join(dir, 'results') });
    }

    if (req.method === 'POST' && url.pathname === '/api/jobs/complete') {
      let body = '';
      for await (const chunk of req) {
        body += chunk.toString('utf8');
        if (body.length > 1024 * 1024) return json(res, 413, { error: 'request too large' });
      }
      const payload = JSON.parse(body || '{}');
      const id = String(payload.id || '');
      const dir = jobPath(id);
      if (!dir || !existsSync(join(dir, 'processing.json'))) return json(res, 404, { error: 'job not claimed' });
      const files = Array.isArray(payload.files) ? payload.files : [];
      for (const f of files) {
        if (typeof f?.name !== 'string' || !/^[\w .()\-]+\.[A-Za-z0-9]{1,8}$/.test(f.name)) {
          return json(res, 400, { error: 'invalid result filename' });
        }
        const path = join(dir, 'results', f.name);
        if (!existsSync(path) || !statSync(path).isFile()) return json(res, 400, { error: `missing result file: ${f.name}` });
      }
      const done = {
        text: String(payload.text || '').slice(0, 500000),
        message: String(payload.message || 'Harness 已完成处理').slice(0, 1000),
        completedAt: new Date().toISOString(),
        files: files.map(f => ({ name: f.name, mimeType: String(f.mimeType || 'application/octet-stream') })),
      };
      writeFileSync(join(dir, 'done.json.tmp'), JSON.stringify(done, null, 2), 'utf8');
      renameSync(join(dir, 'done.json.tmp'), join(dir, 'done.json'));
      return json(res, 200, { ok: true, id, files: listFiles(dir, done) });
    }

    if (req.method === 'GET' && url.pathname === '/api/jobs') {
      const jobs = [];
      for (const id of await readdir(jobsRoot)) {
        const dir = join(jobsRoot, id);
        const job = readJson(join(dir, 'job.json'));
        if (!job) continue;
        const state = jobState(dir, job);
        jobs.push({
          ...job,
          videoPath: join(dir, job.storedName),
          resultsPath: join(dir, 'results'),
          status: state.status,
          ...(state.forwarded ? { forwardedAt: state.forwarded.forwardedAt, targetSessionId: state.forwarded.targetSessionId } : {}),
          ...(readJson(join(dir, 'forward-error.json')) ? { notificationError: readJson(join(dir, 'forward-error.json')).message } : {}),
        });
      }
      jobs.sort((a, b) => a.createdAt.localeCompare(b.createdAt));
      return json(res, 200, { jobs });
    }

    const match = url.pathname.match(/^\/api\/jobs\/([0-9a-f-]{36})(?:\/(status|result|files\/([^/]+)))?$/i);
    if (!match) return json(res, 404, { error: 'not found' });
    const [, id, action = 'status', encodedName] = match;
    const dir = jobPath(id);
    if (!dir || !existsSync(join(dir, 'job.json'))) return json(res, 404, { error: 'job not found' });
    const job = readJson(join(dir, 'job.json'));
    const done = readJson(join(dir, 'done.json'));
    const processing = existsSync(join(dir, 'processing.json'));

    if (req.method === 'GET' && action === 'status') {
      const files = done ? listFiles(dir, done) : [];
      const forwarded = readJson(join(dir, 'forwarded.json'));
      const forwardError = readJson(join(dir, 'forward-error.json'));
      const status = done ? 'complete' : processing ? 'processing' : forwarded ? 'forwarded' : forwardError ? 'notification_failed' : 'uploaded';
      const message = done?.message || (processing ? '电脑 Harness 正在处理' : forwarded ? `已注入目标 Harness 会话 ${forwarded.targetSessionId}` : forwardError ? `已上传，自动通知失败：${forwardError.message}` : '等待会话注入');
      return json(res, 200, { id, status, createdAt: job.createdAt, size: job.size, message, ...(forwardError ? { notificationError: forwardError.message } : {}), files });
    }
    if (req.method === 'GET' && action === 'result') {
      if (!done) return json(res, 409, { error: 'result not ready' });
      return json(res, 200, { id, text: done.text || '', files: listFiles(dir, done), completedAt: done.completedAt });
    }
    if (req.method === 'GET' && action.startsWith('files/')) {
      if (!done) return json(res, 409, { error: 'result not ready' });
      let name;
      try { name = decodeURIComponent(encodedName); } catch { return json(res, 400, { error: 'bad filename' }); }
      const item = listFiles(dir, done).find(f => f.name === name);
      if (!item) return json(res, 404, { error: 'file not found' });
      res.writeHead(200, {
        'Content-Type': item.mimeType,
        'Content-Length': item.size,
        'Content-Disposition': `attachment; filename*=UTF-8''${encodeURIComponent(item.name)}`,
        'Cache-Control': 'no-store',
        'X-Content-Type-Options': 'nosniff',
      });
      return createReadStream(join(dir, 'results', item.name)).pipe(res);
    }
    return json(res, 405, { error: 'method not allowed' });
  } catch (error) {
    console.error('bridge request failed:', error);
    if (!res.headersSent) json(res, 500, { error: 'internal bridge error' });
    else res.destroy(error);
  }
});

server.requestTimeout = 0;
server.headersTimeout = 60_000;
server.keepAliveTimeout = 5_000;
// 双栈：监听 '::' 时 Node 默认 ipv6Only=false，IPv4 连接会以映射地址进来。
const listenArgs = bindHost === '0.0.0.0' ? [] : [bindHost];
server.listen(port, ...listenArgs, () => {
  console.log(`VideoQuiz bridge listening on ${bindHost === '0.0.0.0' ? '0.0.0.0 + ::' : bindHost}:${port}`);
  for (const [name, entries] of Object.entries(networkInterfaces())) {
    for (const entry of entries || []) {
      if (entry.internal) continue;
      if (entry.family === 'IPv4') console.log(`LAN:  http://${entry.address}:${port}  (${name})`);
      else if (entry.family === 'IPv6' && !entry.address.startsWith('fe80')) {
        console.log(`IPv6: http://[${entry.address}]:${port}  (${name})`);
      }
    }
  }
  console.log('Pairing token is stored in bridge/data/token.txt');
  console.log(`Data directory: ${dataRoot}`);
});
