/**
 * 试卷（paper）语义测试 —— 对应 2026-11 文哥口径：
 *   1. 按【模式】分会话：整段 → whole 会话，分段 → segment 会话；
 *      同一套卷的所有分段共用一个分段会话，不按段拆。
 *   2. 同一套试卷内串行：多个分段不并发抢同一会话上下文。
 *   3. 共享上下文：后一段能看到前面已出的答案。
 *   4. 不同试卷可并行，互不阻塞。
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, rmSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { join } from 'node:path';
import {
  UnitQueue,
  resolveSession,
  normalizeTaskMode,
  DEFAULT_PAPER_ID,
  TASK_MODES,
} from '../units.mjs';

const here = fileURLToPath(new URL('.', import.meta.url));
const base = join(here, '.tmp');
mkdirSync(base, { recursive: true });

function makeQueue(handlers = {}) {
  const root = mkdtempSync(join(base, 'paper-'));
  const events = [];
  const queue = new UnitQueue({
    root,
    dispatch: async (unit) => {
      events.push({ type: 'start', hash: unit.hash, paperId: unit.paperId, seq: unit.seq });
      if (handlers.dispatch) await handlers.dispatch(unit, events);
      events.push({ type: 'end', hash: unit.hash, paperId: unit.paperId });
    },
    collectResult: handlers.collectResult ?? ((unit) => handlers.result?.[unit.hash] ?? null),
    taskMode: handlers.taskMode,
  });
  return { queue, root, events, cleanup: () => rmSync(root, { recursive: true, force: true }) };
}

// ── 1. 按模式分会话 ──────────────────────────────────────────────

test('会话按模式分流：整段走 whole，分段走 segment', () => {
  const sessions = { whole: 'session-W', segment: 'session-S' };
  assert.equal(resolveSession({ meta: {}, taskMode: 'whole' }, sessions), 'session-W');
  assert.equal(resolveSession({ meta: {}, taskMode: 'segment' }, sessions), 'session-S');
});

test('同一套卷的多个分段都落在同一个分段会话（不按段拆会话）', () => {
  const sessions = { whole: 'session-W', segment: 'session-S' };
  const routed = [1, 2, 3, 4].map((seq) => resolveSession({ meta: { paperId: 'p1' }, taskMode: 'segment', seq }, sessions));
  assert.deepEqual(routed, ['session-S', 'session-S', 'session-S', 'session-S']);
  assert.equal(new Set(routed).size, 1, '所有分段必须共用同一个会话');
});

test('显式指定 sessionId 时优先于模式默认值', () => {
  const sessions = { whole: 'session-W', segment: 'session-S' };
  assert.equal(
    resolveSession({ meta: { sessionId: 'session-EXPLICIT' }, taskMode: 'segment' }, sessions),
    'session-EXPLICIT',
  );
});

test('整段与分段默认落在不同会话，避免上下文互相污染', () => {
  const sessions = { whole: 'session-W', segment: 'session-S' };
  assert.notEqual(
    resolveSession({ meta: {}, taskMode: 'whole' }, sessions),
    resolveSession({ meta: {}, taskMode: 'segment' }, sessions),
  );
});

test('taskMode 归一化支持别名', () => {
  assert.deepEqual(TASK_MODES, ['whole', 'segment']);
  assert.equal(normalizeTaskMode('incremental'), 'segment');
  assert.equal(normalizeTaskMode('split'), 'segment');
  assert.equal(normalizeTaskMode('full'), 'whole');
  assert.equal(normalizeTaskMode('batch'), 'whole');
  assert.equal(normalizeTaskMode('乱七八糟', 'segment'), 'segment');
});

// ── 2. 同一试卷内串行 ────────────────────────────────────────────

test('同一试卷内串行：前一段结束前，后一段不开始', async () => {
  const { queue, events, cleanup } = makeQueue({
    dispatch: async (unit) => {
      // 模拟耗时，制造并发窗口
      await new Promise((r) => setTimeout(r, 25));
    },
  });
  try {
    const hashes = ['a'.repeat(64), 'b'.repeat(64), 'c'.repeat(64)];
    hashes.forEach((hash, i) => queue.enqueue({ hash, kind: 'image', name: `${i}.png`, paperId: 'same-paper' }));

    await Promise.all(hashes.map((h) => queue.process(h)));

    // 事件序列必须是 start/end/start/end/start/end，不能交错
    const kinds = events.map((e) => e.type);
    assert.deepEqual(kinds, ['start', 'end', 'start', 'end', 'start', 'end'], `实际序列：${kinds.join(',')}`);

    // 每个 start 之前，上一段必须已经 end
    let open = 0;
    for (const e of events) {
      if (e.type === 'start') {
        assert.equal(open, 0, '上一个 unit 还没结束时又开了新的');
        open += 1;
      } else {
        open -= 1;
      }
    }
    assert.equal(open, 0);
  } finally {
    cleanup();
  }
});

// ── 3. 共享上下文与已出答案 ──────────────────────────────────────

test('同一套卷的后续分段能看到前面已出的答案', async () => {
  const result = {
    ['1'.repeat(64)]: { answer: '第1段', items: [{ id: '21', answer: 'B', type: 'choice' }] },
    ['2'.repeat(64)]: { answer: '第2段', items: [{ id: '22', answer: 'A', type: 'choice' }] },
  };
  const seen = [];
  const { queue, cleanup } = makeQueue({
    result,
    dispatch: async (unit) => {
      seen.push({ seq: unit.seq, prior: { ...(unit.context?.priorAnswers ?? {}) } });
    },
  });
  try {
    queue.enqueue({ hash: '1'.repeat(64), kind: 'image', name: 'p1.png', paperId: 'paper-A' });
    queue.enqueue({ hash: '2'.repeat(64), kind: 'image', name: 'p2.png', paperId: 'paper-A' });

    await queue.process('1'.repeat(64));
    await queue.process('2'.repeat(64));

    assert.deepEqual(seen[0].prior, {}, '第 1 段不应该有已有答案');
    assert.deepEqual(seen[1].prior, { '21': 'B' }, '第 2 段应该看得到第 1 段出的 21 题');

    const paper = queue.readPaper('paper-A');
    assert.deepEqual(paper.answers, { '21': 'B', '22': 'A' }, '试卷累积答案应包含两段的结果');
    assert.equal(paper.units.length, 2);
  } finally {
    cleanup();
  }
});

test('同一题号在后续分段被修正时，以新答案覆盖', async () => {
  const result = {
    ['a'.repeat(64)]: { items: [{ id: '5', answer: 'C' }] },
    ['b'.repeat(64)]: { items: [{ id: '5', answer: 'D' }] },
  };
  const { queue, cleanup } = makeQueue({ result });
  try {
    queue.enqueue({ hash: 'a'.repeat(64), kind: 'image', name: '1.png', paperId: 'paper-fix' });
    queue.enqueue({ hash: 'b'.repeat(64), kind: 'image', name: '2.png', paperId: 'paper-fix' });
    await queue.process('a'.repeat(64));
    await queue.process('b'.repeat(64));
    assert.equal(queue.readPaper('paper-fix').answers['5'], 'D');
  } finally {
    cleanup();
  }
});

test('未指定 paperId 时默认归到同一套卷', () => {
  const { queue, cleanup } = makeQueue({});
  try {
    queue.enqueue({ hash: 'x'.repeat(64), kind: 'image', name: 'a.png' });
    queue.enqueue({ hash: 'y'.repeat(64), kind: 'image', name: 'b.png' });
    const list = queue.list();
    assert.deepEqual(list.map((u) => u.paperId), [DEFAULT_PAPER_ID, DEFAULT_PAPER_ID]);
    assert.deepEqual(list.map((u) => u.seq), [1, 2], '同一试卷内 seq 应递增');
  } finally {
    cleanup();
  }
});

// ── 4. 不同试卷互不阻塞 ──────────────────────────────────────────

test('不同试卷可并行处理，不互相等待', async () => {
  const { queue, cleanup } = makeQueue({
    dispatch: async () => {
      await new Promise((r) => setTimeout(r, 30));
    },
  });
  try {
    queue.enqueue({ hash: 'p'.repeat(64), kind: 'image', name: 'p1.png', paperId: 'paper-P' });
    queue.enqueue({ hash: 'q'.repeat(64), kind: 'image', name: 'q1.png', paperId: 'paper-Q' });

    const started = Date.now();
    await Promise.all([queue.process('p'.repeat(64)), queue.process('q'.repeat(64))]);
    const elapsed = Date.now() - started;

    // 串行的话要 60ms+，并行应显著更短
    assert.ok(elapsed < 55, `不同试卷应并行，实际耗时 ${elapsed}ms`);
  } finally {
    cleanup();
  }
});

// ── 4.5 投递 ≠ 完成：必须等结果真的写出来 ────────────────────────

test('dispatch 返回但结果未写出时，unit 不能标 done', async () => {
  const root = mkdtempSync(join(base, 'wait-'));
  const hashes = [];
  let finishedFlag = false;
  const queue = new UnitQueue({
    root,
    // 投递立即返回，模拟「消息已投进会话」
    dispatch: async (unit) => { hashes.push(unit.hash); },
    // 结果要过一会儿才写出来
    isFinished: () => finishedFlag,
    collectResult: () => ({ items: [{ id: '1', answer: 'A' }] }),
    pollIntervalMs: 20,
    resultTimeoutMs: 2000,
  });
  try {
    queue.enqueue({ hash: 'w'.repeat(64), kind: 'image', name: 'w.png', paperId: 'P' });
    const running = queue.process('w'.repeat(64));
    await new Promise((r) => setTimeout(r, 120));
    // 结果没出来，应该还在 running，不能是 done
    assert.equal(queue.get('w'.repeat(64)).status, 'running');
    finishedFlag = true;
    const unit = await running;
    assert.equal(unit.status, 'done');
    assert.equal(unit.answer ?? null, null); // unit 本身不存 answer，走 result.json
    assert.deepEqual(queue.readPaper('P').answers, { '1': 'A' });
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test('结果等不到时标 failed，而不是假装 done', async () => {
  const root = mkdtempSync(join(base, 'timeout-'));
  const queue = new UnitQueue({
    root,
    dispatch: async () => {},
    isFinished: () => false,
    collectResult: () => null,
    pollIntervalMs: 20,
    resultTimeoutMs: 200,
  });
  try {
    queue.enqueue({ hash: 't'.repeat(64), kind: 'image', name: 't.png', paperId: 'P2' });
    const unit = await queue.process('t'.repeat(64));
    assert.equal(unit.status, 'failed');
    assert.match(String(unit.error), /超时/);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

// ── 5. 模式与试卷在状态里持久化 ──────────────────────────────────

test('taskMode 持久化到 units.json，重启后保留', () => {
  const { queue, root, cleanup } = makeQueue({});
  try {
    queue.setTaskMode('segment');
    assert.equal(queue.getTaskMode(), 'segment');

    // 模拟重启：用同一个 root 新建队列
    const reborn = new UnitQueue({ root, dispatch: async () => {}, collectResult: () => null });
    assert.equal(reborn.getTaskMode(), 'segment', '重启后 taskMode 应保留');
  } finally {
    cleanup();
  }
});

test('summarize 同时给出调度模式与任务模式', () => {
  const { queue, cleanup } = makeQueue({});
  try {
    queue.enqueue({ hash: 'z'.repeat(64), kind: 'video-segment', name: 's1.mp4', paperId: 'P', taskMode: 'segment' });
    const summary = queue.summarize();
    assert.equal(summary.total, 1);
    assert.equal(summary.byTaskMode.segment, 1);
    assert.equal(summary.byTaskMode.whole, 0);
    assert.deepEqual(summary.papers, ['P']);
  } finally {
    cleanup();
  }
});
