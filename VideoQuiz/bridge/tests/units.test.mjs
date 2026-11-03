import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, rmSync, readFileSync, existsSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { UnitQueue, hashContent, UNIT_DONE, UNIT_FAILED } from '../units.mjs';

const here = dirname(fileURLToPath(import.meta.url));

function freshRoot() {
  // 放在项目内，避免依赖系统临时目录权限
  const base = join(here, '.tmp');
  mkdirSync(base, { recursive: true });
  return mkdtempSync(join(base, 'run-'));
}

/** 假的识别核心：把「答案」写成 dispatch 收到的内容长度，便于断言逐段产出 */
function makeQueue(root, calls) {
  return new UnitQueue({
    root,
    dispatch: async (unit) => {
      calls.push(unit.hash);
      // 模拟同一 unit 的识别耗时
      await new Promise((r) => setTimeout(r, 5));
      if (unit.name === 'boom.png') throw new Error('识别失败');
    },
    collectResult: (unit) => ({ answer: `答案-${unit.name}`, size: unit.size }),
  });
}

test('逐张/逐段：每个 unit 完成即落 result.json，不攒到最后', async () => {
  const root = freshRoot();
  try {
    const calls = [];
    const q = makeQueue(root, calls);

    const a = Buffer.from('image-A');
    const b = Buffer.from('video-segment-B');
    const ua = q.enqueue({ hash: hashContent(a), kind: 'image', name: 'p1.png', size: a.length });
    const ub = q.enqueue({ hash: hashContent(b), kind: 'video-segment', name: 'seg2.mp4', size: b.length });

    await q.process(ua.unit.hash);

    // 第一个 unit 已完成并落盘，第二个还没跑 —— 证明"不阻塞、逐段产出"
    assert.equal(q.get(ua.unit.hash).status, UNIT_DONE);
    assert.equal(q.get(ub.unit.hash).status, 'pending');
    const r1Path = join(root, ua.unit.hash, 'result.json');
    assert.ok(existsSync(r1Path), '第一个 unit 的结果文件应已存在');
    const r1 = JSON.parse(readFileSync(r1Path, 'utf8'));
    assert.equal(r1.answer.answer, '答案-p1.png');

    await q.process(ub.unit.hash);
    assert.equal(q.get(ub.unit.hash).status, UNIT_DONE);
    assert.ok(existsSync(join(root, ub.unit.hash, 'result.json')));
    const summary1 = q.summarize();
    assert.equal(summary1.total, 2);
    assert.equal(summary1.done, 2);
    assert.equal(summary1.pending, 0);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test('幂等：同一内容重复上传只识别一次', async () => {
  const root = freshRoot();
  try {
    const calls = [];
    const q = makeQueue(root, calls);
    const buf = Buffer.from('same-bytes');
    const hash = hashContent(buf);

    const first = q.enqueue({ hash, kind: 'image', name: 'shot.png', size: buf.length });
    const second = q.enqueue({ hash, kind: 'image', name: 'shot.png', size: buf.length });

    assert.equal(first.duplicate, false);
    assert.equal(second.duplicate, true);
    assert.equal(q.list().length, 1, '同 hash 只应有一个 unit');

    await q.process(hash);
    await q.process(hash); // 已完成再处理是 no-op
    assert.equal(calls.length, 1, '识别核心只应被调用一次');
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test('中断续跑：新进程从同一个 state 恢复 pending/running', async () => {
  const root = freshRoot();
  try {
    const calls1 = [];
    const q1 = makeQueue(root, calls1);
    const b1 = Buffer.from('u1');
    const b2 = Buffer.from('u2');
    q1.enqueue({ hash: hashContent(b1), kind: 'image', name: 'a.png', size: b1.length });
    q1.enqueue({ hash: hashContent(b2), kind: 'image', name: 'b.png', size: b2.length });
    // 只跑一个，然后"崩溃"
    await q1.process(hashContent(b1));

    // 模拟重启：新实例读同一个 units.json
    const calls2 = [];
    const q2 = makeQueue(root, calls2);
    assert.equal(q2.summarize().done, 1);
    const recovered = await q2.resumeAll();
    assert.equal(recovered, 1, '只应恢复未完成的那个');
    assert.equal(q2.summarize().done, 2);
    assert.deepEqual(calls2, [hashContent(b2)]);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test('单个 unit 失败不影响其它 unit', async () => {
  const root = freshRoot();
  try {
    const calls = [];
    const q = makeQueue(root, calls);
    const bad = Buffer.from('bad');
    const good = Buffer.from('good');
    q.enqueue({ hash: hashContent(bad), kind: 'image', name: 'boom.png', size: bad.length });
    q.enqueue({ hash: hashContent(good), kind: 'image', name: 'ok.png', size: good.length });

    await q.process(hashContent(bad));
    await q.process(hashContent(good));

    assert.equal(q.get(hashContent(bad)).status, UNIT_FAILED);
    assert.match(q.get(hashContent(bad)).error, /识别失败/);
    assert.equal(q.get(hashContent(good)).status, UNIT_DONE);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test('模式1 batch：收齐不跑，flush 才跑完（默认模式）', async () => {
  const root = freshRoot();
  try {
    const calls = [];
    const q = new UnitQueue({ root, dispatch: async (u) => { calls.push(u.hash); }, collectResult: (u) => ({ answer: u.name }) });
    assert.equal(q.getMode(), 'batch', '默认必须是 batch');
    q.enqueue({ hash: hashContent(Buffer.from('b1')), kind: 'image', name: 'b1.png', size: 2 });
    q.enqueue({ hash: hashContent(Buffer.from('b2')), kind: 'image', name: 'b2.png', size: 2 });
    assert.equal(calls.length, 0, 'batch 模式下不应该自动跑');
    assert.equal(q.summarize().pending, 2);
    const n = await q.flush();
    assert.equal(n, 2);
    assert.equal(q.summarize().done, 2);
  } finally { rmSync(root, { recursive: true, force: true }); }
});

test('模式2 single：一次一张，处理完即出结果', async () => {
  const root = freshRoot();
  try {
    const q = new UnitQueue({ root, mode: 'single', dispatch: async () => {}, collectResult: (u) => ({ answer: `ans-${u.name}` }) });
    assert.equal(q.getMode(), 'single');
    const h1 = hashContent(Buffer.from('s1'));
    q.enqueue({ hash: h1, kind: 'image', name: 's1.png', size: 2 });
    assert.equal(q.get(h1).status, 'pending');
    await q.processNext();
    assert.equal(q.get(h1).status, 'done', '处理完这张应立即 done');
    assert.ok(existsSync(join(root, h1, 'result.json')));
  } finally { rmSync(root, { recursive: true, force: true }); }
});

test('模式3 video：分段按到达顺序串行处理', async () => {
  const root = freshRoot();
  try {
    const order = [];
    const q = new UnitQueue({ root, mode: 'video', dispatch: async (u) => { order.push(u.name); }, collectResult: () => null });
    assert.equal(q.getMode(), 'video');
    q.enqueue({ hash: hashContent(Buffer.from('v1')), kind: 'video-segment', name: 'seg1.mp4', size: 2 });
    q.enqueue({ hash: hashContent(Buffer.from('v2')), kind: 'video-segment', name: 'seg2.mp4', size: 2 });
    await q.processNext();
    await new Promise((r) => setTimeout(r, 5));
    await q.processNext();
    assert.deepEqual(order, ['seg1.mp4', 'seg2.mp4'], '分段必须按顺序处理');
    assert.equal(q.summarize().done, 2);
  } finally { rmSync(root, { recursive: true, force: true }); }
});

test('模式可切换 + 跨模式续跑：batch 未跑完 → 切 single → 重启后全部跑完', async () => {
  const root = freshRoot();
  try {
    const q1 = new UnitQueue({ root, dispatch: async () => {}, collectResult: () => null });
    const h1 = hashContent(Buffer.from('m1'));
    const h2 = hashContent(Buffer.from('m2'));
    q1.enqueue({ hash: h1, kind: 'image', name: 'm1.png', size: 2 });
    q1.enqueue({ hash: h2, kind: 'image', name: 'm2.png', size: 2 });
    assert.equal(q1.summarize().pending, 2);

    q1.setMode('single');
    assert.equal(q1.getMode(), 'single');

    // 模拟重启：新实例读同一个 units.json，模式与队列都要保留
    const q2 = new UnitQueue({ root, dispatch: async () => {}, collectResult: () => null });
    assert.equal(q2.getMode(), 'single', '模式应跨重启保留');
    const recovered = await q2.resumeAll();
    assert.equal(recovered, 2, '未完成的 unit 应被续跑');
    assert.equal(q2.summarize().done, 2);
  } finally { rmSync(root, { recursive: true, force: true }); }
});

test('跨模式幂等：同一内容在 batch / single 下都只识别一次', async () => {
  const root = freshRoot();
  try {
    const calls = [];
    const q = new UnitQueue({ root, dispatch: async (u) => { calls.push(u.hash); }, collectResult: () => null });
    const buf = Buffer.from('same-across-modes');
    const hash = hashContent(buf);
    const a = q.enqueue({ hash, kind: 'image', name: 'x.png', size: buf.length, mode: 'batch' });
    const b = q.enqueue({ hash, kind: 'image', name: 'x.png', size: buf.length, mode: 'single' });
    assert.equal(a.duplicate, false);
    assert.equal(b.duplicate, true, '换模式也不能重复识别');
    await q.process(hash);
    assert.equal(calls.length, 1);
  } finally { rmSync(root, { recursive: true, force: true }); }
});
