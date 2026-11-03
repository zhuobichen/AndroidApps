import { createServer } from 'node:http';
import { createReadStream, createWriteStream, mkdirSync, readFileSync, writeFileSync, renameSync, existsSync, statSync, unlinkSync, readdirSync } from 'node:fs';
import { readdir } from 'node:fs/promises';
import { randomBytes, timingSafeEqual, randomUUID } from 'node:crypto';
import { pipeline } from 'node:stream/promises';
import { Transform } from 'node:stream';
import { basename, extname, join, resolve } from 'node:path';
import { networkInterfaces } from 'node:os';
import { fileURLToPath } from 'node:url';
import { spawn } from 'node:child_process';

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
const workspaceStatePath = join(process.env.DSH_HOME || 'D:/github_project/ZhaoWen_GitHub维护/dsh/home', 'storages', 'workspace.json');

/**
 * 当前工作区的会话集合。
 *
 * 权威来源是 `$DSH_HOME/storages/workspace.json`：DSH 侧栏就是按「工作区内的会话 − 已归档」
 * 来显示的。用目录名猜会多算（同 cwd 下的子代理会话也在里面），所以优先读这个文件；
 * 读不到才退回按会话目录分组猜测。
 */
function workspaceSessionIds() {
  try {
    const state = JSON.parse(readFileSync(workspaceStatePath, 'utf8'));
    const archived = new Set(Array.isArray(state.global?.archivedSessionIds) ? state.global.archivedSessionIds : []);
    const workspaces = state.tables?.workspaces ?? {};
    const pinned = String(process.env.VIDEOQUIZ_WORKSPACE_ID || '').trim();
    const pinnedTitle = String(process.env.VIDEOQUIZ_WORKSPACE_TITLE || '').trim();
    for (const [id, ws] of Object.entries(workspaces)) {
      const ids = Array.isArray(ws?.sessionIds) ? ws.sessionIds : [];
      const matches = pinned !== ''
        ? id === pinned
        : pinnedTitle !== ''
          ? String(ws?.title || '') === pinnedTitle
          : ids.includes(anchorSessionId);
      if (!matches) continue;
      return {
        id,
        group: typeof ws.title === 'string' && ws.title !== '' ? ws.title : id,
        ids: ids.filter((x) => !archived.has(x)),
        archived: [...archived].filter((x) => ids.includes(x)).length,
        source: 'workspace.json',
      };
    }
  } catch {
    // 继续走下面的目录推断
  }

  try {
    for (const group of readdirSync(sessionsRoot)) {
      const groupDir = join(sessionsRoot, group);
      if (!existsSync(join(groupDir, anchorSessionId))) continue;
      const ids = readdirSync(groupDir).filter((name) => /^(session-)?[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(name));
      return { group, ids, source: 'sessions-dir' };
    }
  } catch {
    // 退回「不限制」，并在返回里如实标 scope=unrestricted
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

/** 派发出去多久还没回完成标记，就认为那次解析已经中断（兜底，主要靠会话是否在跑）。 */
const STALL_MINUTES = Number(process.env.VIDEOQUIZ_STALL_MINUTES || 45);
/** 刚派发的前几分钟允许会话启动，不判死。 */
const STARTUP_GRACE_MINUTES = 3;

/** 常驻 OCR 服务：模型只加载一次、CUDA 只预热一次，省掉每次 50 秒冷启动。 */
const ocrServerUrl = process.env.VIDEOQUIZ_OCR_SERVER || 'http://127.0.0.1:8766';
let ocrChild = null;

async function ocrServerReady(timeoutMs = 3000) {
  try {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs);
    const response = await fetch(`${ocrServerUrl}/health`, { signal: controller.signal });
    clearTimeout(timer);
    if (!response.ok) return false;
    const data = await response.json();
    return data?.ready === true;
  } catch {
    return false;
  }
}

async function ensureOcrServer() {
  if (await ocrServerReady()) {
    console.log(`ocr: 复用已有服务 ${ocrServerUrl}`);
    return;
  }

  const script = join(here, 'ocr_server.py');
  if (!existsSync(script)) {
    console.log('ocr: 缺少 ocr_server.py，跳过');
    return;
  }

  try {
    ocrChild = spawn(process.env.VIDEOQUIZ_PYTHON || 'python', [script, '--port', '8766', '--cuda', 'auto'], {
      cwd: here,
      // detached：OCR 服务独立于桥接，桥接重启不会连带杀掉它，50 秒预热只付一次
      detached: true,
      stdio: 'ignore',
      windowsHide: true,
    });
    ocrChild.on('error', (error) => console.log(`ocr: 启动失败 ${error.message}`));
    ocrChild.unref();
    console.log('ocr: 正在启动常驻服务（首次要预热约 50 秒，期间会自动回退 CPU）');

    // 后台等它就绪，不阻塞桥接启动
    for (let i = 0; i < 40; i += 1) {
      await new Promise((resolve) => setTimeout(resolve, 3000));
      if (await ocrServerReady()) {
        console.log(`ocr: 服务就绪 ${ocrServerUrl}`);
        return;
      }
    }
    console.log('ocr: 等待超时，会话会回退到本地模型');
  } catch (error) {
    console.log(`ocr: 启动异常 ${error.message}`);
  }
}

/** Jev（typesafeai）判定层：只做选择题选答案，不给过程。配置在 data/jev.json。 */
const jevConfigPath = join(dataRoot, 'jev.json');

function jevConfig() {
  try {
    const raw = JSON.parse(readFileSync(jevConfigPath, 'utf8'));
    if (typeof raw?.baseUrl !== 'string' || typeof raw?.apiKey !== 'string') return null;
    return {
      baseUrl: raw.baseUrl,
      apiKey: raw.apiKey,
      model: typeof raw.model === 'string' && raw.model !== '' ? raw.model : 'jev-latest',
      batchSize: Number(raw.batchSize) > 0 ? Number(raw.batchSize) : 8,
      stateCharLimit: Number(raw.stateCharLimit) > 0 ? Number(raw.stateCharLimit) : 7000,
      minConfidence: Number.isFinite(Number(raw.minConfidence)) ? Number(raw.minConfidence) : 0.6,
      timeoutMs: Number(raw.timeoutMs) > 0 ? Number(raw.timeoutMs) : 60000,
    };
  } catch {
    return null;
  }
}

/** 把一道题压成 Jev 的 state 行。 */
function jevStateLine(question, index) {
  const options = question.options ?? {};
  const optionText = Object.entries(options).map(([key, text]) => `${key}) ${String(text).replace(/\s+/g, ' ').trim()}`).join('  ');
  return `[#${index}] ${String(question.stem ?? '').replace(/\s+/g, ' ').trim()}\n选项：${optionText}`;
}

/** 分批调 Jev，返回 { 题号 -> {choice, confidence} }。 */
async function judgeWithJev(questions, config) {
  const results = new Map();
  const warnings = [];

  let batch = [];
  let batchChars = 0;

  const flush = async () => {
    if (batch.length === 0) return;

    const state = batch.map((q, i) => jevStateLine(q, i)).join('\n');
    const payload = {
      model: config.model,
      state,
      questions: Object.fromEntries(batch.map((q, i) => [`m${i}`, {
        type: 'choice',
        instructions: `[#${i}] 这道题的正确答案是哪个选项？只选一个。`,
        criteria: q.options ?? {},
      }])),
    };

    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), config.timeoutMs);

    try {
      const response = await fetch(config.baseUrl, {
        method: 'POST',
        headers: { Authorization: `Bearer ${config.apiKey}`, 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
        signal: controller.signal,
      });
      const text = await response.text();
      if (!response.ok) throw new Error(`HTTP ${response.status} ${text.slice(0, 200)}`);

      const data = JSON.parse(text);
      for (let i = 0; i < batch.length; i += 1) {
        const answer = data?.answers?.[`m${i}`];
        if (answer === undefined) continue;
        results.set(batch[i].id, {
          choice: answer.choice,
          confidence: typeof answer.confidence === 'number' ? answer.confidence : 0,
        });
      }
    } catch (error) {
      warnings.push(`batch ${batch.length} 题失败：${error.message}`);
    } finally {
      clearTimeout(timer);
    }

    batch = [];
    batchChars = 0;
  };

  for (const question of questions) {
    const line = jevStateLine(question, batch.length);
    if (batch.length >= config.batchSize || (batchChars + line.length > config.stateCharLimit && batch.length > 0)) {
      await flush();
    }
    batch.push(question);
    batchChars += line.length + 40;
  }
  await flush();

  return { results, warnings };
}

/** 会话最近活动缓存：status 每次轮询都问插件太重，4 秒内复用。 */
const activityCache = new Map();

async function latestActivity(sessionId) {
  if (typeof sessionId !== 'string' || sessionId === '') return undefined;

  const hit = activityCache.get(sessionId);
  if (hit !== undefined && Date.now() - hit.at < 4000) return hit.text;

  try {
    const outcome = await supervisorCommand({ op: 'tail', sessionId, limit: 6, includeTools: true });
    const messages = outcome?.body?.messages;
    if (Array.isArray(messages) && messages.length > 0) {
      const last = messages[messages.length - 1];
      const text = typeof last.text === 'string' ? last.text.trim() : '';
      const line = text === '' ? undefined : `${last.role === 'tool' ? '正在 ' : ''}${text}`;
      activityCache.set(sessionId, { at: Date.now(), text: line });
      return line;
    }
  } catch {
    // 插件不可达就跳过，不影响状态查询
  }

  activityCache.set(sessionId, { at: Date.now(), text: undefined });
  return undefined;
}

/** 缓存「哪些会话正在跑」，避免每个请求都问一次插件。 */
let runningCache = { at: 0, ids: new Set(), ok: false };

async function refreshRunningSessions() {
  if (Date.now() - runningCache.at < 8000) return runningCache;
  try {
    const outcome = await supervisorCommand({ op: 'list' });
    const sessions = outcome?.body?.sessions;
    if (Array.isArray(sessions)) {
      const ids = new Set();
      for (const session of sessions) {
        if (session && session.running && typeof session.sessionId === 'string') ids.add(session.sessionId);
      }
      runningCache = { at: Date.now(), ids, ok: true };
      return runningCache;
    }
  } catch {
    // 插件不可达就退回时间判断
  }
  runningCache = { at: Date.now(), ids: new Set(), ok: false };
  return runningCache;
}

function jobState(dir, job) {
  const done = readJson(join(dir, 'done.json'));
  const forwarding = readJson(join(dir, 'forwarding.json'));
  const forwarded = readJson(join(dir, 'forwarded.json'));
  const processing = existsSync(join(dir, 'processing.json'));
  const cancelled = existsSync(join(dir, 'cancelled.json'));

  // 已派发但长时间没有 done.json：可能是解析被中断（DSH 重启、会话被杀），
  // 这时不能一直显示「处理中」，要明确告诉用户它停了。
  let stalled = false;
  let idleMinutes = 0;
  let reason;
  if (job.mode !== 'shot' && done === null && (processing || forwarded !== null || forwarding !== null)) {
    const newest = newestMtime(dir);
    idleMinutes = Math.floor((Date.now() - newest) / 60000);
    const target = forwarded?.targetSessionId || job.targetSessionId;
    const running = runningCache.ok && target ? runningCache.ids.has(target) : null;

    if (idleMinutes >= STALL_MINUTES) {
      stalled = true;
      reason = `超过 ${STALL_MINUTES} 分钟没有结果`;
    } else if (running === false && idleMinutes >= STARTUP_GRACE_MINUTES) {
      // 会话根本没在跑，说明那次解析已经停了
      stalled = true;
      reason = '负责这个任务的会话已经不在运行';
    }
  }

  return {
    done,
    processing,
    forwarded,
    forwarding,
    stalled,
    idleMinutes,
    ...(reason === undefined ? {} : { stallReason: reason }),
    cancelled,
    status: cancelled && done === null
      ? 'cancelled'
      : done
      ? 'complete'
      : stalled
        ? 'stalled'
        : processing
          ? 'processing'
          : forwarded
            ? 'forwarded'
            : forwarding
              ? 'forwarding'
              : job.status || 'uploaded',
  };
}

/** 目录里最新的文件修改时间，用来判断任务是否还在推进。 */
function newestMtime(dir) {
  let newest = 0;
  try {
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      const full = join(dir, entry.name);
      const stamp = statSync(full).mtimeMs;
      if (stamp > newest) newest = stamp;
    }
  } catch {
    return Date.now();
  }
  return newest || Date.now();
}

async function sendToSession(id, job, options = {}) {
  const dir = jobPath(id);
  if (!dir || !existsSync(join(dir, 'job.json'))) throw new Error('job not found');

  if (options.force === true) {
    // 重新派发：把上一次的投递记录归档，然后当作没派发过处理
    for (const name of ['forwarding.json', 'forwarded.json', 'forward-error.json']) {
      const from = join(dir, name);
      if (existsSync(from)) {
        try { renameSync(from, join(dir, `${name}.previous-${Date.now()}`)); } catch { try { unlinkSync(from); } catch {} }
      }
    }
  } else if (existsSync(join(dir, 'forwarding.json')) || existsSync(join(dir, 'forwarded.json'))) {
    return;
  }

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
      ``,
      `【第 0 步：先 claim，立刻做，别做别的】运行 task.ps1 -Operation claim -BaseUrl ${completionUrl} -Id ${id}。不 claim 的话手机端一直显示「已投递」，用户会以为卡住了。claim 完再开始干活。`,
      `【关于帧去重，这条最影响速度】滚动录屏的相邻帧重叠 80% 以上，直接全量 OCR 是最大的时间浪费——实测 296 帧全量 OCR 花了 18 分钟，而真正不重复的页面只有 30-40 个。所以：`,
      `  - 帧数超过 200 的视频必须先完成位移去重，但方法选错会白跑：`,
      `      ✗ 不要用「灰度行均值/列均值互相关」：手持拍摄有抖动，而且网页不同区域的均值特征常常相似，实测只会保留个位数帧、直接失效。`,
      `      ✓ 用「模板匹配」：取上一帧中部的横条当模板，在当前帧里沿纵向做归一化互相关搜索，峰值位置就是位移。实测在手持浏览器滚动场景下得分 0.9 以上。`,
      `      ✓ 或「相位相关」：两帧转灰度做 FFT，用互功率谱相位求平移量。对纯平移有效，抖动大时会失真。`,
      `  - 验收判据（关键）：去重后的帧数要和内容量匹配——74 秒的滚动录屏通常保留 40~120 帧。`,
      `    如果保留帧数少于总帧数的 10%（比如 2222 帧只剩 2 帧），说明位移估计失败了，**立刻换方法，不要调参数硬凑**；`,
      `    如果两种方法都失败，退回固定间隔抽帧（每 3~4 帧取一张）再靠题号连续性回补缺口。`,
      `  - 先用低分辨率（宽 480）跑位移估计，这一步很便宜，不要上全分辨率；`,
      `  - 去重后再对保留的帧做 OCR。296 帧压到 40 帧，OCR 时间从 18 分钟降到约 2.5 分钟；`,
      `  - 宁可多留 20% 的帧，也不要漏掉题目——去重后要检查题号是否连续，发现断号就回补前后帧。`,
      `【必须执行，跳过视为任务未完成】这套流程有一条硬性要求：选择题由专用判定模型来判，不是你自己判。它一次能判上百题、11 秒出结果、成本不到一厘钱，而且每条都带置信度——有把握的它给、没把握的留给你。你自己从零推理整卷选择题要花十几分钟，属于浪费。所以下面第 2 步不能省。`,
      `【成本和速度同样是硬指标，不影响正确率的前提下必须省】读图是最贵的操作：一张 1920×1080 的帧约 7000 tokens、约 7 秒；一张 827×1170 的整页图约 4900 tokens。整卷读图次数超过 10 次就是在烧钱。具体做法：`,
      `  a) 先把视频切页、定位出「不同页面」，每页截一张整页图存进 work/，之后就只读这些整页图；`,
      `  b) 每页最多读 1 次。切页完成后把原始帧图删掉，避免不自觉地回去逐帧核对——那是最费钱的坏习惯；`,
      `  c) 读图前把长边缩到 1000 像素。桥接目录已经备好缩放脚本，直接跑：python "${join(here, 'shrink.py')}" <图片目录> 1000 —— 它会生成 _small/ 子目录（实测 1920×1080 缩到 1000×562，体积省 77%，文字公式依然清晰）。读 _small/ 里的图，不要读原图；`,
      `  d) OCR 直接调桥接目录的现成脚本，别自己拼：python "${join(here, 'ocr.py')}" <图片目录> --out <结果.json>。它会自动启用 CUDA（你的机器有 N 卡，实测从 2.4 秒/帧降到 0.4 秒/帧，快 6 倍）、自动预热、自动回落 CPU，并输出结构化的行文本+坐标+置信度。只有 OCR 置信度低或版式不明的段落才读图核对；`,
      `  e) 拿不准的段落用 OCR 文本 + 相邻页交叉验证，不要靠重复读图。`,
      `量化目标（45 题规模的卷子）：读图不超过 8 次、读题阶段 60 秒内完成、总 token 控制在 5 万以内。做不到就在结果摘要里说明卡在哪。`,
      `第 1 步（只读题，先别解题）：读视频里的每一道题，把题干和选项结构化写进 ${resultsPath}\\questions.json，格式严格如下：`,
      `{"questions":[{"id":"21","type":"choice","stem":"题干原文","options":{"A":"选项A原文","B":"选项B原文","C":"选项C原文","D":"选项D原文"},"needsFigure":false,"incomplete":false},{"id":"36","type":"blank","stem":"题干原文","options":null}]}`,
      `字段要求：type 取 choice / blank / essay；只有 choice 要填 options，且【必须四个选项齐全】——少一个就把 incomplete 置 true（判定模型看不到完整选项只会瞎猜，反而浪费）；题干保留题号、公式、原文，不要改写或翻译。`,
      `材料必须写进 stem：阅读理解的原文段落、资料分析的统计表格、完形填空的整段短文，全部用「材料：」开头放在题干最前面，然后才是问题。表格转成「列名: 值」的纯文本行。只写问题不写材料，判定模型一定判不了——这是外包能不能用起来的分水岭。`,
      `needsFigure：依赖图形/示意图（图形推理、几何配图、图表数据拿不到）置 true，这类跳过外包由你自己解。`,
      `第 2 步（必须做）：写好 questions.json 后，立即执行下面这条命令，不要跳过、不要改成自己做：`,
      `  & "${join(here, 'task.ps1')}" -Operation ask-jev -BaseUrl "${completionUrl}" -Id "${id}"`,
      `它返回 JSON：answered 是判定模型给出且置信度达标的答案（直接采用，不要重新推理），unresolved 是它也没把握的题号（这些由你自己解），skippedFigure 是被跳过的图形题数量。如果这条命令报错（比如没配置判定模型），就把所有题都自己解，并在结果摘要里说明。`,
      `第 3 步：answered 里的题直接抄它的答案；unresolved 里的题、所有 blank/essay、needsFigure=true 的题，由你自己认真解。`,
      `第 4 步：合并成 answers.pdf（完整版，题目+答案）、answers_only.pdf（速查版，只列题号与答案）和 answers.txt；然后运行 task.ps1：Operation=complete、BaseUrl=${completionUrl}、Id=${id}、Text=结果摘要（要写明判定模型覆盖了多少题、你自己解了多少题）、FilesJson='[{"name":"answers.pdf","mimeType":"application/pdf"},{"name":"answers_only.pdf","mimeType":"application/pdf"},{"name":"answers.txt","mimeType":"text/plain"}]'。脚本自行读取本机 token.txt，禁止读取/展示/写入对话。不要改写视频源文件。`,
      `进度上报（可选但推荐）：每完成一个阶段可运行 task.ps1 -Operation progress -BaseUrl ${completionUrl} -Id ${id} -Text "现在做到哪一步"，手机端会实时显示。`,
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
          const inScope = (Array.isArray(outcome.body.sessions) ? outcome.body.sessions : []).filter(s => allowed.has(s.sessionId));
          // 与侧栏一致：工作区内未归档的会话；默认再滤掉从未命名的空会话。
          const all = url.searchParams.get('all') === '1';
          const visible = all ? inScope : inScope.filter((s) => typeof s.title === 'string' && s.title.trim() !== '');
          // 侧栏按最近活跃倒序，这里保持一致
          visible.sort((a, b) => String(b.updatedAt || '').localeCompare(String(a.updatedAt || '')));
          return json(res, 200, {
            ok: true,
            count: visible.length,
            total: inScope.length,
            archived: scope.archived ?? 0,
            workspace: scope.group,
            source: scope.source,
            scope: 'workspace',
            sessions: visible,
          });
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

    // 在本工作区新建一个空会话（不发消息），供「每次任务一个会话」使用
    if (req.method === 'POST' && url.pathname === '/api/sessions/create') {
      let payload;
      try {
        payload = await readJsonBody(req, 16 * 1024);
      } catch (error) {
        return json(res, 400, { ok: false, code: 'BAD_JSON', message: error.message });
      }
      const scope = workspaceSessionIds();
      try {
        // 新会话默认必须是完全权限，否则写答案文件时还要人工批准
        await supervisorCommand({ op: 'ensureFullAccess' });
        const outcome = await supervisorCommand({
          op: 'createSession',
          ...(scope === null ? { cwd: workspacePath } : { workspaceId: scope.id }),
          ...(typeof payload.title === 'string' && payload.title.trim() !== '' ? { title: payload.title.trim() } : {}),
          ...(typeof payload.agentPreset === 'string' && payload.agentPreset.trim() !== '' ? { agentPreset: payload.agentPreset.trim() } : {}),
        });
        const created = outcome.body ?? {};
        const fresh = typeof created.sessionId === 'string' ? created.sessionId : '';
        if (fresh !== '') {
          // 建完再对这个会话单独切一次，覆盖已存在的会话
          try {
            await supervisorCommand({ op: 'ensureFullAccess', sessionId: fresh });
          } catch (error) {
            console.warn(`session ${fresh} created but permission switch failed: ${error.message}`);
          }
        }
        return json(res, outcome.status === undefined ? 200 : outcome.status, outcome.body);
      } catch (error) {
        return json(res, 502, { ok: false, code: 'SUPERVISOR_UNREACHABLE', message: error.message });
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
        // 客户端可以要求「先只存不发」，等它显式调 /forward 再派发
        const noDispatch = String(req.headers['x-videoquiz-no-dispatch'] || '') === '1';
        if (noDispatch) {
          return json(res, 201, { id, size: received, status: 'uploaded', dispatched: false });
        }

        let forwarded = false;
        try {
          await sendToSession(id, task);
          forwarded = true;
        } catch (error) {
          console.warn(`job ${id} uploaded but management-session notification failed: ${error.message}`);
        }
        return json(res, 201, { id, size: received, status: forwarded ? 'forwarded' : 'notification_failed', dispatched: forwarded });
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
        // 已派发过但没产出结果的任务（中断），允许强制重投
        const force = ['1','true'].includes(String(url.searchParams.get('force') || '').toLowerCase());
        await sendToSession(id, job, { force });
        return json(res, 200, { ok: true, id, status: 'forwarded', forced: force });
      } catch (error) {
        return json(res, 502, { ok: false, id, status: 'notification_failed', error: error.message });
      }
    }

    // 逐题模式：创建一个没有视频的容器任务，之后每拍一张就往里追加
    if (req.method === 'POST' && url.pathname === '/api/jobs/shot-start') {
      let payload;
      try {
        payload = await readJsonBody(req, 16 * 1024);
      } catch (error) {
        return json(res, 400, { ok: false, code: 'BAD_JSON', message: error.message });
      }

      const wanted = typeof payload.sessionId === 'string' ? payload.sessionId.trim() : '';
      const scope = workspaceSessionIds();
      let session = wanted === '' ? targetSessionId : wanted;

      if (scope !== null && !scope.ids.includes(session)) {
        session = scope.ids.includes(targetSessionId) ? targetSessionId : scope.ids[0];
      }
      if (!session) return json(res, 409, { ok: false, code: 'NO_SESSION' });

      const id = randomUUID();
      const dir = join(jobsRoot, id);
      mkdirSync(dir, { recursive: true });
      mkdirSync(join(dir, 'results'), { recursive: true });
      mkdirSync(join(dir, 'shots'), { recursive: true });

      const job = {
        id,
        mode: 'shot',
        originalName: '逐题模式',
        storedName: '',
        size: 0,
        status: 'shot-mode',
        targetSessionId: session,
        task: '逐题作答',
        createdAt: new Date().toISOString(),
      };

      writeFileSync(join(dir, 'job.json'), JSON.stringify(job, null, 2), 'utf8');
      writeFileSync(
        join(dir, 'forwarded.json'),
        JSON.stringify({ targetSessionId: session, forwardedAt: new Date().toISOString(), mode: 'shot' }),
        'utf8',
      );

      return json(res, 200, { ok: true, id, sessionId: session });
    }

    // 判定外包：读 results/questions.json，交给 Jev 批量选答案，
    // 高置信的直接采用，低置信的返回给会话精解。
    if (req.method === 'POST' && url.pathname === '/api/jobs/ask-jev') {
      let payload;
      try {
        payload = await readJsonBody(req, 16 * 1024);
      } catch (error) {
        return json(res, 400, { ok: false, code: 'BAD_JSON', message: error.message });
      }

      const id = typeof payload.id === 'string' ? payload.id : '';
      const dir = id === '' ? null : jobPath(id);
      if (!dir) return json(res, 404, { ok: false, code: 'JOB_NOT_FOUND' });

      const config = jevConfig();
      if (config === null) {
        return json(res, 503, {
          ok: false,
          code: 'JEV_NOT_CONFIGURED',
          message: '桥接目录下缺少 data/jev.json（需要 baseUrl / apiKey / model）',
        });
      }

      const questionsPath = join(dir, 'results', 'questions.json');
      if (!existsSync(questionsPath)) {
        return json(res, 404, { ok: false, code: 'QUESTIONS_NOT_FOUND', message: 'results/questions.json 不存在' });
      }

      let parsed;
      try {
        parsed = JSON.parse(readFileSync(questionsPath, 'utf8'));
      } catch (error) {
        return json(res, 400, { ok: false, code: 'QUESTIONS_INVALID', message: error.message });
      }

      const all = Array.isArray(parsed?.questions) ? parsed.questions : [];
      // 只判「纯文本可判」的选择题：
      // - 必须有 options（至少 2 个）
      // - 标了 needsFigure 的跳过（图形题看不到图，判了也是瞎猜）
      // - 题干太短（多半是缺材料）也跳过
      const choiceable = all.filter((q) => {
        if (q === null || typeof q !== "object") return false;
        if (typeof q.id !== "string") return false;
        if (q.needsFigure === true) return false;
        // 会话自己标了「选项不全」就别判，看不全只会瞎猜
        if (q.incomplete === true) return false;
        if (!q.options) return false;
        // 选择题必须 A/B/C/D 四个选项齐全，缺一个就跳过
        const optionKeys = Object.keys(q.options);
        if (optionKeys.length < 4) return false;
        if (!["A", "B", "C", "D"].every((k) => optionKeys.includes(k))) return false;
        const stem = typeof q.stem === "string" ? q.stem.trim() : "";
        return stem.length >= 12;
      });

      const skippedFigure = all.filter((q) => q !== null && typeof q === "object" && q.needsFigure === true).length;
      if (choiceable.length === 0) {
        return json(res, 200, { ok: true, id, judged: 0, message: '没有可判定的选择题' });
      }

      const started = Date.now();
      const { results, warnings } = await judgeWithJev(choiceable, config);

      const decided = [];
      const needHelp = [];

      for (const question of choiceable) {
        const hit = results.get(question.id);
        if (hit === undefined || typeof hit.choice !== 'string') {
          needHelp.push({ id: question.id, reason: 'no_answer' });
          continue;
        }
        if (hit.confidence >= config.minConfidence) {
          decided.push({ id: question.id, answer: hit.choice, confidence: hit.confidence });
        } else {
          needHelp.push({ id: question.id, reason: 'low_confidence', guess: hit.choice, confidence: hit.confidence });
        }
      }

      const out = {
        ok: true,
        id,
        model: config.model,
        minConfidence: config.minConfidence,
        judged: choiceable.length,
        decided: decided.length,
        needHelp: needHelp.length,
        ...(skippedFigure > 0 ? { skippedFigure } : {}),
        answered: decided,
        unresolved: needHelp,
        elapsedMs: Date.now() - started,
        ...(warnings.length > 0 ? { warnings } : {}),
      };

      writeFileSync(join(dir, 'results', 'jev-answers.json'), JSON.stringify(out, null, 2), 'utf8');
      return json(res, 200, out);
    }

    // 逐题模式：往已有任务里追加一张题目照片，原样落盘后通知会话作答
    const shotMatch = url.pathname.match(/^\/api\/jobs\/([0-9a-f-]{36})\/shot$/i);
    if (req.method === 'POST' && shotMatch) {
      const id = shotMatch[1];
      const dir = jobPath(id);
      const job = dir ? readJson(join(dir, 'job.json')) : null;
      if (!job) return json(res, 404, { ok: false, code: 'JOB_NOT_FOUND' });

      const length = Number(req.headers['content-length']);
      if (!Number.isSafeInteger(length) || length <= 0) {
        return json(res, 411, { ok: false, code: 'LENGTH_REQUIRED' });
      }

      const shotDir = join(dir, 'shots');
      mkdirSync(shotDir, { recursive: true });

      const index = (readdirSync(shotDir).filter((n) => /^q\d+\./.test(n)).length + 1);
      const ext = (() => {
        const raw = req.headers['x-shot-name-b64']
          ? Buffer.from(String(req.headers['x-shot-name-b64']), 'base64').toString('utf8')
          : 'shot.jpg';
        const e = extname(String(raw)).toLowerCase();
        return ['.jpg', '.jpeg', '.png', '.webp', '.heic'].includes(e) ? e : '.jpg';
      })();

      const fileName = `q${index}${ext}`;
      const target = join(shotDir, fileName);
      const tmp = join(shotDir, `${fileName}.uploading`);

      try {
        await pipeline(req, createWriteStream(tmp, { flags: 'wx' }));
        const size = statSync(tmp).size;
        if (size !== length) throw new Error(`length mismatch: expected ${length}, received ${size}`);
        renameSync(tmp, target);
      } catch (error) {
        try { unlinkSync(tmp); } catch {}
        return json(res, 500, { ok: false, code: 'SHOT_WRITE_FAILED', message: error.message });
      }

      // 通知会话：同一会话继续答，上下文保持连续
      const forwardedInfo = readJson(join(dir, 'forwarded.json'));
      const sessionId = (forwardedInfo && forwardedInfo.targetSessionId) || job.targetSessionId || targetSessionId;
      const scope = workspaceSessionIds();
      const inScope = scope === null || scope.ids.includes(sessionId);

      if (!inScope) {
        return json(res, 403, { ok: false, code: 'OUT_OF_WORKSPACE', message: '目标会话不在当前工作区' });
      }

      const prompt = [
        `逐题模式：第 ${index} 题的题目照片已保存到本机。`,
        `图片路径：${target}`,
        `结果目录：${join(dir, 'results')}`,
        '请读取这张图，识别题目原文（保留题号、公式），给出解答与答案。',
        '把本题结果追加到结果目录的 answers.txt（没有就新建，按题号顺序累积），不要覆盖前面题目的内容。',
        '不需要重新生成整卷 PDF；等用户说“出卷”时再统一排版。',
        '原图是相机原图，如果文字太小看不清，可以裁切或放大后再读。',
      ].join('\n');

      try {
        const outcome = await supervisorCommand({ op: 'send', sessionId, text: prompt });
        if (outcome.status !== 200 || outcome.body.ok !== true) {
          throw new Error(outcome.body.message || outcome.body.code || `HTTP ${outcome.status}`);
        }
      } catch (error) {
        return json(res, 502, { ok: false, code: 'NOTIFY_FAILED', message: error.message, index, path: target });
      }

      return json(res, 200, { ok: true, id, index, path: target, sessionId });
    }

    // 追问：读某个任务对应会话的最近消息 / 往该会话再发一条
    const chatMatch = url.pathname.match(/^\/api\/jobs\/([0-9a-f-]{36})\/chat$/i);
    if (chatMatch) {
      const id = chatMatch[1];
      const dir = jobPath(id);
      const job = dir ? readJson(join(dir, 'job.json')) : null;
      if (!job) return json(res, 404, { ok: false, code: 'JOB_NOT_FOUND' });

      const forwardedInfo = readJson(join(dir, 'forwarded.json'));
      const sessionId = (forwardedInfo && forwardedInfo.targetSessionId)
        || job.targetSessionId
        || targetSessionId;

      if (req.method === 'GET') {
        const limit = Number(url.searchParams.get('limit') || 30);
        try {
          const outcome = await supervisorCommand({ op: 'tail', sessionId, limit });
          return json(res, outcome.status === undefined ? 200 : outcome.status, { ...outcome.body, sessionId });
        } catch (error) {
          return json(res, 502, { ok: false, code: 'SUPERVISOR_UNREACHABLE', message: error.message });
        }
      }

      if (req.method === 'POST') {
        let payload;
        try {
          payload = await readJsonBody(req, 32 * 1024);
        } catch (error) {
          return json(res, 400, { ok: false, code: 'BAD_JSON', message: error.message });
        }
        const text = typeof payload.text === 'string' ? payload.text : '';
        if (text.trim() === '') return json(res, 400, { ok: false, code: 'EMPTY_TEXT' });
        try {
          const outcome = await supervisorCommand({ op: 'send', sessionId, text });
          return json(res, outcome.status === undefined ? 200 : outcome.status, outcome.body);
        } catch (error) {
          return json(res, 502, { ok: false, code: 'SUPERVISOR_UNREACHABLE', message: error.message });
        }
      }

      return json(res, 405, { ok: false, code: 'METHOD_NOT_ALLOWED' });
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
      return json(res, 200, { ...job, ...(job.storedName ? { videoPath: join(dir, job.storedName) } : {}), resultsPath: join(dir, 'results') });
    }

    // 会话主动上报进度：追加到 progress.json，App 轮询时显示「现在做到哪一步」
    if (req.method === 'POST' && url.pathname === '/api/jobs/progress') {
      let payload;
      try {
        payload = await readJsonBody(req, 16 * 1024);
      } catch (error) {
        return json(res, 400, { ok: false, code: 'BAD_JSON', message: error.message });
      }

      const id = typeof payload.id === 'string' ? payload.id : '';
      const text = typeof payload.text === 'string' ? payload.text.trim() : '';
      const dir = id === '' ? null : jobPath(id);

      if (!dir || !existsSync(join(dir, 'job.json'))) return json(res, 404, { ok: false, code: 'JOB_NOT_FOUND' });
      if (text === '') return json(res, 400, { ok: false, code: 'EMPTY_TEXT' });

      const file = join(dir, 'progress.json');
      let entries = [];
      try {
        const existing = readJson(file);
        if (Array.isArray(existing)) entries = existing;
      } catch {}

      entries.push({ at: new Date().toISOString(), text });
      // 只留最近 50 条，避免文件无限增长
      if (entries.length > 50) entries = entries.slice(-50);
      writeFileSync(file, JSON.stringify(entries), 'utf8');

      return json(res, 200, { ok: true, id, count: entries.length });
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
      await refreshRunningSessions();
      const jobs = [];
      for (const id of await readdir(jobsRoot)) {
        const dir = join(jobsRoot, id);
        const job = readJson(join(dir, 'job.json'));
        if (!job) continue;
        const state = jobState(dir, job);
        jobs.push({
          ...job,
          ...(job.storedName ? { videoPath: join(dir, job.storedName) } : {}),
          resultsPath: join(dir, 'results'),
          status: state.status,
          ...(readJson(join(dir, 'forwarded.json'))
            ? {
                targetSessionId: readJson(join(dir, 'forwarded.json')).targetSessionId,
                targetSessionTitle: readJson(join(dir, 'forwarded.json')).targetTitle,
              }
            : (job.targetSessionId ? { targetSessionId: job.targetSessionId } : {})),
          ...(state.forwarded ? { forwardedAt: state.forwarded.forwardedAt, targetSessionId: state.forwarded.targetSessionId } : {}),
          ...(readJson(join(dir, 'forward-error.json')) ? { notificationError: readJson(join(dir, 'forward-error.json')).message } : {}),
        });
      }
      jobs.sort((a, b) => a.createdAt.localeCompare(b.createdAt));
      return json(res, 200, { jobs });
    }

    const match = url.pathname.match(/^\/api\/jobs\/([0-9a-f-]{36})(?:\/(status|result|cancel|resume|files\/([^/]+)))?$/i);
    if (!match) return json(res, 404, { error: 'not found' });
    const [, id, action = 'status', encodedName] = match;
    await refreshRunningSessions();
    const dir = jobPath(id);
    if (!dir || !existsSync(join(dir, 'job.json'))) return json(res, 404, { error: 'job not found' });
    const job = readJson(join(dir, 'job.json'));
    const done = readJson(join(dir, 'done.json'));
    const processing = existsSync(join(dir, 'processing.json'));
  const cancelled = existsSync(join(dir, 'cancelled.json'));

    if (req.method === 'GET' && action === 'status') {
      const files = done ? listFiles(dir, done) : [];
      const forwarded = readJson(join(dir, 'forwarded.json'));
      const forwardError = readJson(join(dir, 'forward-error.json'));
      const computed = jobState(dir, job);
      const stalled = computed.stalled === true;
      // 直接读会话正在做什么（最近的工具调用 / 助手发言），不依赖会话主动上报
      const targetSession = (forwarded && forwarded.targetSessionId) || job.targetSessionId;
      const activity = computed.status === 'processing' || computed.status === 'forwarded'
        ? await latestActivity(targetSession)
        : undefined;
      const status = stalled ? 'stalled' : computed.status;
      const message = stalled
        ? `这个任务停在电脑上（${computed.stallReason || '没有产出结果'}），可以重新派发`
        : computed.status === 'cancelled'
        ? '这个任务已被取消'
        : done?.message || (processing ? '电脑 Harness 正在处理' : forwarded ? `已注入目标 Harness 会话 ${forwarded.targetSessionId}` : forwardError ? `已上传，自动通知失败：${forwardError.message}` : '等待会话注入');
      const progressLog = (() => {
        try {
          const entries = readJson(join(dir, 'progress.json'));
          return Array.isArray(entries) ? entries.slice(-5) : [];
        } catch {
          return [];
        }
      })();

      return json(res, 200, {
        ...(progressLog.length > 0 ? { progress: progressLog } : {}),
        ...(activity === undefined ? {} : { activity }),
        id,
        status,
        createdAt: job.createdAt,
        size: job.size,
        message,
        ...(computed.idleMinutes ? { idleMinutes: computed.idleMinutes } : {}),
        ...(forwardError ? { notificationError: forwardError.message } : {}),
        files,
      });
    }
    if (req.method === 'POST' && action === 'cancel') {
      writeFileSync(join(dir, 'cancelled.json'), JSON.stringify({ cancelledAt: new Date().toISOString() }));
      return json(res, 200, { ok: true, id, status: 'cancelled' });
    }
    if (req.method === 'POST' && action === 'resume') {
      try { unlinkSync(join(dir, 'cancelled.json')); } catch {}
      return json(res, 200, { ok: true, id, status: 'uploaded' });
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
  ensureOcrServer().catch((error) => console.log(`ocr: ${error.message}`));
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
