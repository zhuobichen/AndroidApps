/**
 * 分段/分张流水队列（Unit Queue）
 *
 * 解决的问题：现有流水线是「收齐材料 → 一次批量识别」。实际有两种用法需要逐段出结果：
 *   A. 只传图片、一张一张传：每来一张就先出这张的答案，再看下一张
 *   B. 视频一段一段传：一段处理完再收下一段
 *
 * 设计要点：
 *   - 单位是 unit（一张图 / 一段视频），unit 之间互不阻塞
 *   - 状态持久化到 units.json（原子写），进程重启后可续跑
 *   - 同一 unit 重复上传按**内容 sha256** 幂等去重，不重复识别
 *   - 每个 unit 完成即写 result 文件（不攒到最后）
 *   - 与老路径完全解耦：老 job 协议不受影响
 *
 * 同一试卷（paper）语义（2026-11 文哥口径）：
 *   - 分段任务默认同属一套试卷，**不拆会话**：所有 unit 落在同一个 targetSession，
 *     共享同一份试卷上下文与已出答案。
 *   - 区分的是【模式】不是【会话】：
 *       taskMode = 'whole'  整段模式 —— 一次提交完整材料（整段视频 / 整卷图）
 *       taskMode = 'segment' 分段模式 —— 分多次提交（多段视频 / 多张相片）
 *   - 并发互斥靠**同一试卷内串行**：同 paperId 的 unit 按入队顺序逐个处理，
 *     不并发抢会话上下文；不同 paperId 互不阻塞。
 */
import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, readdirSync, renameSync, writeFileSync, unlinkSync } from 'node:fs';
import { join } from 'node:path';

export const UNIT_PENDING = 'pending';
export const UNIT_RUNNING = 'running';
export const UNIT_DONE = 'done';
export const UNIT_FAILED = 'failed';

/**
 * 三种识别模式（同一套 unit 核心，只有「何时触发处理」不同）：
 *   batch   —— 收齐一批再跑，全量跑完统一出结果（默认，兼容老行为）
 *   single  —— 一次一张，到货即处理，出完这张立刻可读结果
 *   video   —— 一段一段，到货即处理，按到达顺序串行（保证时间线不乱）
 */
export const MODES = ['batch', 'single', 'video'];
export const DEFAULT_MODE = 'batch';

/**
 * 任务模式：整段 vs 分段（文哥口径，对外可见的区分）
 *   whole   —— 整段模式：一次提交完整材料（整段视频 / 整卷相片）
 *   segment —— 分段模式：分多次提交（多段视频 / 逐张相片）
 * 同一套试卷的多个分段共享同一个 paperId，进而共享上下文与已出答案。
 */
export const TASK_MODES = ['whole', 'segment'];
export const DEFAULT_TASK_MODE = 'whole';

export function normalizeTaskMode(value, fallback = DEFAULT_TASK_MODE) {
  const raw = String(value ?? '').trim().toLowerCase();
  if (TASK_MODES.includes(raw)) return raw;
  if (raw === 'batch' || raw === 'full' || raw === 'single-file') return 'whole';
  if (raw === 'incremental' || raw === 'multi' || raw === 'split') return 'segment';
  return fallback;
}

export function normalizeMode(value, fallback = DEFAULT_MODE) {
  const raw = String(value ?? '').trim().toLowerCase();
  if (MODES.includes(raw)) return raw;
  if (raw === 'incremental' || raw === 'incremental-single' || raw === 'shot') return 'single';
  if (raw === 'incremental-video' || raw === 'segment') return 'video';
  return fallback;
}

/** 默认试卷 id：没显式指定时，所有分段都归到同一套试卷 */
export const DEFAULT_PAPER_ID = 'default';

/**
 * 决定一个 unit 该派到哪个会话（纯函数，便于测试）。
 *
 * 文哥口径（2026-11）：按【模式】分会话，不按【段】分会话。
 *   1. 调用方显式指定 sessionId → 用它；
 *   2. 否则整段模式走 whole 会话，分段模式走 segment 会话；
 *   3. 同一套试卷的所有分段共用一个分段会话，不按段拆。
 *
 * @param {{meta?: object, taskMode?: string}} unit
 * @param {{whole?: string, segment?: string}} sessions
 * @returns {string}
 */
export function resolveSession(unit, sessions = {}) {
  const explicit = unit && unit.meta && typeof unit.meta.sessionId === 'string'
    ? unit.meta.sessionId.trim()
    : '';
  if (explicit !== '') return explicit;

  const mode = normalizeTaskMode(
    (unit && unit.taskMode) || (unit && unit.meta && unit.meta.taskMode),
    DEFAULT_TASK_MODE,
  );
  const whole = sessions.whole || sessions.fallback || '';
  const segment = sessions.segment || sessions.fallback || '';
  return mode === 'segment' ? segment : whole;
}


/** 内容 hash：同一段内容无论传几次都是同一个 unit */
export function hashContent(buffer) {
  return createHash('sha256').update(buffer).digest('hex');
}

/** 原子写：先写 .tmp 再 rename，避免断电留下半个 json */
function writeJsonAtomic(path, value) {
  const tmp = `${path}.tmp`;
  writeFileSync(tmp, JSON.stringify(value, null, 2), 'utf8');
  renameSync(tmp, path);
}

export class UnitQueue {
  /**
   * @param {object} options
   * @param {string} options.root     unit 数据根目录（如 bridge/data/units）
   * @param {(unit: object) => Promise<void>} options.dispatch  把 unit 派给识别核心
   * @param {(unit: object) => boolean} [options.isFinished]    可选：判断 unit 是否已产出结果
   * @param {(unit: object) => object|null} [options.collectResult] 可选：收集该 unit 的答案
   */
  constructor({ root, dispatch, isFinished, collectResult, mode, taskMode, resultTimeoutMs, pollIntervalMs }) {
    this.root = root;
    this.dispatch = dispatch;
    this.isFinished = typeof isFinished === 'function' ? isFinished : null;
    this.collectResult = collectResult ?? (() => null);
    this.statePath = join(root, 'units.json');
    this.initialMode = mode === undefined ? undefined : normalizeMode(mode);
    this.initialTaskMode = taskMode === undefined ? undefined : normalizeTaskMode(taskMode);
    this.state = this.#load();
    this.running = new Set();
    /** 每个试卷一条串行链：同 paperId 的 unit 排队跑，不同 paperId 互不阻塞 */
    this.paperChains = new Map();
    /**
     * 派发只是把消息投进会话，会话是异步处理的——必须等它真的产出 answer.json
     * 才能算 done，否则会出现「状态已完成、结果为空」，续跑时还会把没跑完的段跳过。
     */
    this.resultTimeoutMs = Number(resultTimeoutMs) > 0 ? Number(resultTimeoutMs) : 20 * 60 * 1000;
    this.pollIntervalMs = Number(pollIntervalMs) > 0 ? Number(pollIntervalMs) : 2000;
  }

  /** 当前调度模式（batch / single / video） */
  getMode() {
    return normalizeMode(this.state.mode, DEFAULT_MODE);
  }

  /** 当前任务模式（whole 整段 / segment 分段） */
  getTaskMode() {
    return normalizeTaskMode(this.state.taskMode, this.initialTaskMode ?? DEFAULT_TASK_MODE);
  }

  setTaskMode(taskMode) {
    const next = normalizeTaskMode(taskMode, this.getTaskMode());
    if (next !== this.state.taskMode) {
      this.state.taskMode = next;
      this.save();
    }
    return this.state.taskMode;
  }

  /** 显式切换模式；只影响「何时处理」，不影响已入队 unit 与 hash 幂等 */
  setMode(mode) {
    const next = normalizeMode(mode, this.getMode());
    if (next !== this.state.mode) {
      this.state.mode = next;
      this.save();
    }
    return this.state.mode;
  }


  #load() {
    if (!existsSync(this.root)) mkdirSync(this.root, { recursive: true });
    if (!existsSync(this.statePath)) {
      return {
        version: 1,
        mode: this.initialMode ?? DEFAULT_MODE,
        taskMode: this.initialTaskMode ?? DEFAULT_TASK_MODE,
        units: {},
      };
    }
    try {
      const parsed = JSON.parse(readFileSync(this.statePath, 'utf8'));
      if (!parsed || typeof parsed !== 'object' || typeof parsed.units !== 'object' || parsed.units === null) {
        return {
          version: 1,
          mode: this.initialMode ?? DEFAULT_MODE,
          taskMode: this.initialTaskMode ?? DEFAULT_TASK_MODE,
          units: {},
        };
      }
      return {
        ...parsed,
        mode: normalizeMode(parsed.mode, this.initialMode ?? DEFAULT_MODE),
        taskMode: normalizeTaskMode(parsed.taskMode, this.initialTaskMode ?? DEFAULT_TASK_MODE),
      };
    } catch {
      // 状态损坏时不要整个队列瘫痪：留个备份，从空队列继续
      try { renameSync(this.statePath, `${this.statePath}.corrupt-${Date.now()}`); } catch {}
      return {
        version: 1,
        mode: this.initialMode ?? DEFAULT_MODE,
        taskMode: this.initialTaskMode ?? DEFAULT_TASK_MODE,
        units: {},
      };
    }
  }

  save() {
    writeJsonAtomic(this.statePath, this.state);
  }

  /** 该 unit 的落盘目录 */
  dirFor(hash) {
    return join(this.root, hash);
  }

  /** 试卷累积文件路径：同一套试卷的多个 unit 共享这里 */
  paperPath(paperId) {
    return join(this.root, '_papers', `${String(paperId || DEFAULT_PAPER_ID)}.json`);
  }

  /** 读试卷累积状态（不存在则给空骨架） */
  readPaper(paperId) {
    const id = String(paperId || DEFAULT_PAPER_ID);
    const path = this.paperPath(id);
    const empty = { paperId: id, title: null, createdAt: null, updatedAt: null, units: [], answers: {}, details: {}, notes: [] };
    if (!existsSync(path)) return empty;
    try {
      const parsed = JSON.parse(readFileSync(path, 'utf8'));
      return {
        paperId: id,
        title: typeof parsed.title === 'string' && parsed.title.trim() !== '' ? parsed.title : null,
        createdAt: parsed.createdAt ?? null,
        updatedAt: parsed.updatedAt ?? null,
        units: Array.isArray(parsed.units) ? parsed.units : [],
        answers: parsed.answers && typeof parsed.answers === 'object' ? parsed.answers : {},
        details: parsed.details && typeof parsed.details === 'object' ? parsed.details : {},
        notes: Array.isArray(parsed.notes) ? parsed.notes : [],
      };
    } catch {
      return empty;
    }
  }

  savePaper(paper) {
    const path = this.paperPath(paper.paperId);
    mkdirSync(join(this.root, '_papers'), { recursive: true });
    writeJsonAtomic(path, paper);
  }

  /** 给试卷起名，方便在历史里区分。空标题等价于清掉名字。 */
  setPaperTitle(paperId, title) {
    const paper = this.readPaper(paperId);
    const clean = String(title ?? '').trim().slice(0, 60);
    paper.title = clean === '' ? null : clean;
    paper.updatedAt = new Date().toISOString();
    this.savePaper(paper);
    return paper;
  }

  /**
   * 列出所有做过的试卷（按最近更新倒序）。
   * 用户「换一套新卷」之后还能从历史里找回之前那套。
   */
  listPapers() {
    const dir = join(this.root, '_papers');
    if (!existsSync(dir)) return [];
    const out = [];
    for (const name of readdirSync(dir)) {
      if (!name.endsWith('.json')) continue;
      const path = join(dir, name);
      try {
        const parsed = JSON.parse(readFileSync(path, 'utf8'));
        const id = parsed.paperId ?? name.replace(/\.json$/, '');
        out.push({
          paperId: id,
          createdAt: parsed.createdAt ?? null,
          updatedAt: parsed.updatedAt ?? null,
          answerCount: parsed.answers ? Object.keys(parsed.answers).length : 0,
          unitCount: Array.isArray(parsed.units) ? parsed.units.length : 0,
          title: parsed.title ?? null,
        });
      } catch {
        // 坏文件跳过，不影响其它试卷
      }
    }
    out.sort((a, b) => String(b.updatedAt ?? '').localeCompare(String(a.updatedAt ?? '')));
    return out;
  }

  /** 把某个 unit 的结果并入试卷：答案按题号累积，后来的覆盖前面的 */
  mergeIntoPaper(unit, result) {
    const paper = this.readPaper(unit.paperId);
    const now = new Date().toISOString();
    if (!paper.createdAt) paper.createdAt = now;
    paper.updatedAt = now;

    if (!paper.units.some((u) => u.hash === unit.hash)) {
      paper.units.push({
        hash: unit.hash,
        seq: unit.seq ?? 0,
        kind: unit.kind,
        name: unit.name,
        finishedAt: unit.finishedAt ?? now,
      });
    }

    const items = Array.isArray(result?.items) ? result.items : [];
    for (const item of items) {
      if (!item || item.id === undefined || item.id === null || String(item.id) === '') continue;
      const id = String(item.id);
      // 新格式：brief 只放答案本身，detail 放解析。旧格式只有 answer，兼容之。
      const brief = item.brief ?? item.answer ?? null;
      const detail = item.detail ?? (item.brief !== undefined ? item.answer : null) ?? null;
      paper.answers[id] = brief;
      if (detail !== null && detail !== undefined && String(detail).trim() !== '') {
        paper.details[id] = String(detail);
      }
    }
    if (result?.notes) {
      paper.notes.push({ hash: unit.hash, notes: String(result.notes).slice(0, 2000) });
    }

    this.savePaper(paper);
    return paper;
  }

  /**
   * 同一试卷内串行：把 fn 排到该 paper 队的队尾。
   * 不同 paper 各有一条链，互不阻塞；同 paper 的 unit 严格按入队顺序跑，
   * 保证不会两个 unit 同时往同一个会话上下文里塞东西。
   */
  runInPaper(paperId, fn) {
    const key = String(paperId || DEFAULT_PAPER_ID);
    const prev = this.paperChains.get(key) ?? Promise.resolve();
    const next = prev.then(fn, fn);
    this.paperChains.set(key, next.catch(() => {}));
    return next;
  }

  /**
   * 入队（幂等）。
   * @returns {{unit: object, duplicate: boolean}}
   */
  enqueue({ hash, kind, name, size = 0, meta = {}, mode, paperId, taskMode }) {
    const existing = this.state.units[hash];
    if (existing) {
      return { unit: existing, duplicate: true };
    }

    const pid = String(paperId || meta.paperId || DEFAULT_PAPER_ID);
    // 同一试卷内的序号：用来告诉会话「这是第几段」，也用来保证重放顺序
    const seq = this.list().filter((u) => (u.paperId ?? DEFAULT_PAPER_ID) === pid).length + 1;

    const unit = {
      hash,
      kind,                        // 'image' | 'video-segment'
      mode: normalizeMode(mode ?? this.getMode(), DEFAULT_MODE),
      taskMode: normalizeTaskMode(taskMode ?? this.getTaskMode(), DEFAULT_TASK_MODE),
      paperId: pid,
      seq,
      name,
      size,
      status: UNIT_PENDING,
      createdAt: new Date().toISOString(),
      startedAt: null,
      finishedAt: null,
      attempts: 0,
      error: null,
      resultPath: null,
      meta,
    };
    mkdirSync(this.dirFor(hash), { recursive: true });
    this.state.units[hash] = unit;
    this.save();
    return { unit, duplicate: false };
  }

  list() {
    return Object.values(this.state.units).sort((a, b) => String(a.createdAt).localeCompare(String(b.createdAt)));
  }

  get(hash) {
    return this.state.units[hash] ?? null;
  }

  /** 启动时把中断的 unit（pending/running）捡起来 */
  recoverable() {
    return this.list().filter((u) => u.status === UNIT_PENDING || u.status === UNIT_RUNNING);
  }

  /**
   * 等会话真正产出结果。
   * dispatch 只是「投递消息」，会话异步处理，投递成功 ≠ 处理完成。
   * 没传 isFinished 的调用方（例如单测）跳过等待，保持原有语义。
   */
  async waitForResult(unit) {
    if (!this.isFinished) return true;
    const deadline = Date.now() + this.resultTimeoutMs;
    while (Date.now() < deadline) {
      if (this.isFinished(unit)) return true;
      await new Promise((resolve) => setTimeout(resolve, this.pollIntervalMs));
    }
    return false;
  }

  /**
   * 跑一个 unit：置 running → dispatch → 等结果 → 收结果 → 写 result.json → 并入试卷 → 置 done。
   * 同一试卷内串行（runInPaper），单 paper 内的 unit 不会并发抢同一个会话上下文；
   * 单个 unit 失败不影响其它 unit。
   */
  async process(hash) {
    const unit = this.state.units[hash];
    if (!unit) throw new Error(`unknown unit ${hash}`);
    if (unit.status === UNIT_DONE) return unit;
    if (this.running.has(hash)) return unit;

    const pid = unit.paperId ?? DEFAULT_PAPER_ID;

    return this.runInPaper(pid, async () => {
      // 排队期间可能已被别的调用跑完，二次确认
      if (unit.status === UNIT_DONE) return unit;
      if (this.running.has(hash)) return unit;

      this.running.add(hash);
      unit.status = UNIT_RUNNING;
      unit.startedAt = new Date().toISOString();
      unit.attempts = (unit.attempts ?? 0) + 1;
      this.save();

      try {
        // 把「同一套试卷的上下文」交给 dispatch：
        // 会话据此知道自己是第几段、前面已经出了哪些答案，避免重复识别与自相矛盾。
        const paper = this.readPaper(pid);
        unit.context = {
          paperId: pid,
          seq: unit.seq,
          taskMode: unit.taskMode,
          priorAnswers: { ...paper.answers },
          priorUnitCount: paper.units.length,
        };

        await this.dispatch(unit);

        // 关键：投递成功 ≠ 处理完成。必须等会话真把 answer.json 写出来，
        // 否则会把「还没算完」的段标成 done，续跑时直接跳过。
        const ok = await this.waitForResult(unit);
        if (!ok) {
          throw new Error(`等待结果超时（${Math.round(this.resultTimeoutMs / 1000)}s 内没等到 answer.json）`);
        }

        unit.status = UNIT_DONE;
        unit.finishedAt = new Date().toISOString();
        unit.error = null;
        const result = this.collectResult(unit);
        const resultPath = join(this.dirFor(hash), 'result.json');
        writeJsonAtomic(resultPath, {
          hash,
          kind: unit.kind,
          name: unit.name,
          paperId: pid,
          seq: unit.seq,
          finishedAt: unit.finishedAt,
          answer: result ?? null,
        });
        unit.resultPath = resultPath;

        // 并入试卷累积：同一套试卷的已出答案在这里汇合
        this.mergeIntoPaper(unit, result);
      } catch (error) {
        unit.status = UNIT_FAILED;
        unit.finishedAt = new Date().toISOString();
        unit.error = String(error?.message ?? error);
      } finally {
        delete unit.context;
        this.running.delete(hash);
        this.save();
      }
      return unit;
    });
  }

  /**
   * 续跑：把所有可恢复 unit 重新派发。
   * 并发仅在不同试卷之间生效——同一试卷的 unit 由 process() 内的 paper 串行链排队，
   * 所以这里给多大并发都不会让同一套试卷的两个 unit 同时跑。
   * @returns {Promise<number>} 被恢复的 unit 数
   */
  async resumeAll({ concurrency = 2 } = {}) {
    const pending = this.recoverable().map((u) => u.hash);
    if (pending.length === 0) return 0;

    let cursor = 0;
    const worker = async () => {
      while (cursor < pending.length) {
        const hash = pending[cursor++];
        try { await this.process(hash); } catch { /* 单个失败不影响其它 */ }
      }
    };
    await Promise.all(Array.from({ length: Math.min(concurrency, pending.length) }, worker));
    return pending.length;
  }

  /** 下一个待跑 unit（按入队顺序，video 模式的串行也靠它） */
  nextPending() {
    return this.list().find((u) => u.status === UNIT_PENDING) ?? null;
  }

  /**
   * 跑下一个待处理 unit（single / video 模式用：到货即处理）。
   * @returns {Promise<object|null>} 处理过的 unit，没有则 null
   */
  async processNext() {
    const unit = this.nextPending();
    if (!unit) return null;
    return this.process(unit.hash);
  }

  /**
   * 批量模式：把当前所有 pending 一次跑完（batch 模式用：收齐再跑）。
   * 与 resumeAll 同理：并发只在跨试卷之间生效，同试卷由 paper 串行链排队。
   * @returns {Promise<number>} 处理数量
   */
  async flush({ concurrency = 2 } = {}) {
    const pending = this.list().filter((u) => u.status === UNIT_PENDING).map((u) => u.hash);
    if (pending.length === 0) return 0;

    let cursor = 0;
    const worker = async () => {
      while (cursor < pending.length) {
        const hash = pending[cursor++];
        try { await this.process(hash); } catch { /* 单个失败不影响其它 */ }
      }
    };
    await Promise.all(Array.from({ length: Math.min(concurrency, pending.length) }, worker));
    return pending.length;
  }

  summarize() {
    const all = this.list();
    const by = { pending: 0, running: 0, done: 0, failed: 0 };
    const byMode = { batch: 0, single: 0, video: 0 };
    const byTaskMode = { whole: 0, segment: 0 };
    const papers = new Set();
    for (const u of all) {
      by[u.status] = (by[u.status] ?? 0) + 1;
      const m = normalizeMode(u.mode, DEFAULT_MODE);
      byMode[m] = (byMode[m] ?? 0) + 1;
      const tm = normalizeTaskMode(u.taskMode, DEFAULT_TASK_MODE);
      byTaskMode[tm] = (byTaskMode[tm] ?? 0) + 1;
      papers.add(u.paperId ?? DEFAULT_PAPER_ID);
    }
    return {
      total: all.length,
      mode: this.getMode(),
      taskMode: this.getTaskMode(),
      byMode,
      byTaskMode,
      papers: [...papers],
      ...by,
    };
  }

  /** 列出某套试卷的 unit（按 seq 顺序） */
  listByPaper(paperId) {
    const pid = String(paperId || DEFAULT_PAPER_ID);
    return this.list()
      .filter((u) => (u.paperId ?? DEFAULT_PAPER_ID) === pid)
      .sort((a, b) => (a.seq ?? 0) - (b.seq ?? 0));
  }
}
