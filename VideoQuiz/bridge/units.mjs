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
 */
import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, renameSync, writeFileSync, unlinkSync } from 'node:fs';
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

export function normalizeMode(value, fallback = DEFAULT_MODE) {
  const raw = String(value ?? '').trim().toLowerCase();
  if (MODES.includes(raw)) return raw;
  if (raw === 'incremental' || raw === 'incremental-single' || raw === 'shot') return 'single';
  if (raw === 'incremental-video' || raw === 'segment') return 'video';
  return fallback;
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
  constructor({ root, dispatch, isFinished, collectResult, mode }) {
    this.root = root;
    this.dispatch = dispatch;
    this.isFinished = isFinished ?? (() => false);
    this.collectResult = collectResult ?? (() => null);
    this.statePath = join(root, 'units.json');
    this.initialMode = mode === undefined ? undefined : normalizeMode(mode);
    this.state = this.#load();
    this.running = new Set();
  }

  /** 当前模式（持久化在 units.json 里，跨重启保留） */
  getMode() {
    return normalizeMode(this.state.mode, DEFAULT_MODE);
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
    if (!existsSync(this.statePath)) return { version: 1, mode: this.initialMode ?? DEFAULT_MODE, units: {} };
    try {
      const parsed = JSON.parse(readFileSync(this.statePath, 'utf8'));
      if (!parsed || typeof parsed !== 'object' || typeof parsed.units !== 'object' || parsed.units === null) {
        return { version: 1, mode: this.initialMode ?? DEFAULT_MODE, units: {} };
      }
      return { ...parsed, mode: normalizeMode(parsed.mode, this.initialMode ?? DEFAULT_MODE) };
    } catch {
      // 状态损坏时不要整个队列瘫痪：留个备份，从空队列继续
      try { renameSync(this.statePath, `${this.statePath}.corrupt-${Date.now()}`); } catch {}
      return { version: 1, mode: this.initialMode ?? DEFAULT_MODE, units: {} };
    }
  }

  save() {
    writeJsonAtomic(this.statePath, this.state);
  }

  /** 该 unit 的落盘目录 */
  dirFor(hash) {
    return join(this.root, hash);
  }

  /**
   * 入队（幂等）。
   * @returns {{unit: object, duplicate: boolean}}
   */
  enqueue({ hash, kind, name, size = 0, meta = {}, mode }) {
    const existing = this.state.units[hash];
    if (existing) {
      return { unit: existing, duplicate: true };
    }
    const unit = {
      hash,
      kind,                        // 'image' | 'video-segment'
      mode: normalizeMode(mode ?? this.getMode(), DEFAULT_MODE),
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
   * 跑一个 unit：置 running → dispatch → 收结果 → 写 result.json → 置 done。
   * 单个 unit 失败不影响其它 unit。
   */
  async process(hash) {
    const unit = this.state.units[hash];
    if (!unit) throw new Error(`unknown unit ${hash}`);
    if (unit.status === UNIT_DONE) return unit;
    if (this.running.has(hash)) return unit;

    this.running.add(hash);
    unit.status = UNIT_RUNNING;
    unit.startedAt = new Date().toISOString();
    unit.attempts = (unit.attempts ?? 0) + 1;
    this.save();

    try {
      await this.dispatch(unit);
      unit.status = UNIT_DONE;
      unit.finishedAt = new Date().toISOString();
      unit.error = null;
      const result = this.collectResult(unit);
      const resultPath = join(this.dirFor(hash), 'result.json');
      writeJsonAtomic(resultPath, {
        hash,
        kind: unit.kind,
        name: unit.name,
        finishedAt: unit.finishedAt,
        answer: result ?? null,
      });
      unit.resultPath = resultPath;
    } catch (error) {
      unit.status = UNIT_FAILED;
      unit.finishedAt = new Date().toISOString();
      unit.error = String(error?.message ?? error);
    } finally {
      this.running.delete(hash);
      this.save();
    }
    return unit;
  }

  /**
   * 续跑：把所有可恢复 unit 重新派发（不阻塞，并发跑）。
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
    for (const u of all) {
      by[u.status] = (by[u.status] ?? 0) + 1;
      const m = normalizeMode(u.mode, DEFAULT_MODE);
      byMode[m] = (byMode[m] ?? 0) + 1;
    }
    return { total: all.length, mode: this.getMode(), byMode, ...by };
  }
}
