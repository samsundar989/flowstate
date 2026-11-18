// node --test --experimental-strip-types --no-warnings tests/
// Layout checks on every fixture, at every growth step: no NaN, no overlaps, timing.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { DEFAULT_LAYOUT, layoutGraph } from '../src/graph/layout.ts';

const FIX_DIR = new URL('../../../agents/fixtures/', import.meta.url);
const NODE_SIZE = 0.06; // visual node diameter (design s2: 6 cm)
const fixtures = readdirSync(FIX_DIR).filter((f) => f.endsWith('.jsonl')).sort();

function nodesOf(file) {
  return readFileSync(new URL(file, FIX_DIR), 'utf8')
    .split(/\r?\n/u)
    .filter((l) => l.trim())
    .map((l) => JSON.parse(l))
    .filter((e) => e.kind === 'step_start' && e.step_id)
    .map((e) => ({
      id: e.step_id,
      lane: e.agent_id,
      parent: e.parent_step_id ?? null,
      deps: e.depends_on ?? [],
      retryOf: e.retry_of,
    }));
}

function check(r, label) {
  const vis = r.nodes.filter((n) => n.layer >= r.firstLayer);
  for (const n of r.nodes) {
    for (const k of ['x', 'y', 'z']) assert.ok(Number.isFinite(n[k]), `${label}: ${n.id}.${k} = ${n[k]}`);
  }
  for (let i = 0; i < vis.length; i++) {
    for (let j = i + 1; j < vis.length; j++) {
      const a = vis[i];
      const b = vis[j];
      const d = Math.hypot(a.x - b.x, a.y - b.y, a.z - b.z);
      assert.ok(d >= NODE_SIZE - 1e-9, `${label}: ${a.id} / ${b.id} overlap (${d.toFixed(3)} m)`);
    }
  }
  // Edges point upward or sideways, never into a lower layer (upward growth).
  const byId = new Map(r.nodes.map((n) => [n.id, n]));
  for (const e of r.edges) {
    assert.ok(byId.has(e.from) && byId.has(e.to), `${label}: dangling edge ${e.from}>${e.to}`);
    if (e.kind !== 'retry') assert.ok(byId.get(e.to).layer > byId.get(e.from).layer, `${label}: edge ${e.from}>${e.to} not upward`);
  }
  // Within the graph volume (design s2: 0.80 w, y -0.15..+0.34, z 1.0..1.4).
  for (const n of vis) {
    assert.ok(Math.abs(n.x) <= 0.4 && n.z >= 1.0 && n.z <= 1.4 && n.y >= -0.151 && n.y <= 0.341, `${label}: ${n.id} outside volume`);
  }
}

const variants = [
  ['collapsed', DEFAULT_LAYOUT],
  ['uncollapsed', { ...DEFAULT_LAYOUT, collapseOver: Infinity }],
];

for (const f of fixtures) {
  test(`layout ${f}`, () => {
    const all = nodesOf(f);
    for (const [name, opts] of variants) {
      for (let k = 1; k <= all.length; k++) check(layoutGraph(all.slice(0, k), opts), `${f} ${name} n=${k}`);
    }
    // every id is drawn exactly once (itself or via a "+N" node)
    const r = layoutGraph(all);
    assert.equal(r.drawnAs.size, all.length);
    // timing: median of 20 full layouts
    const ts = [];
    for (let i = 0; i < 20; i++) {
      const t0 = performance.now();
      layoutGraph(all);
      ts.push(performance.now() - t0);
    }
    ts.sort((a, b) => a - b);
    const p50 = ts[10];
    console.log(`TIMING ${f} nodes=${all.length} drawn=${r.nodes.length} layers=${r.maxLayer + 1} p50=${p50.toFixed(3)}ms max=${ts[19].toFixed(3)}ms`);
    assert.ok(ts[19] < 20, `${f}: ${ts[19]} ms`);
  });
}

test('a 13-wide sibling layer collapses to "+N" and expands on request', () => {
  // The real run's root has fan-out 13, but depends_on spreads it over 4 layers (max 4 per
  // lane-layer), so the collapse is exercised on a flat 13-child fan-out.
  const all = [{ id: 'b.root', lane: 'builder', parent: null, deps: [] }];
  for (let i = 0; i < 13; i++) all.push({ id: `b.${i}`, lane: 'builder', parent: 'b.root', deps: [] });
  const r = layoutGraph(all);
  const agg = r.nodes.filter((n) => n.members);
  assert.equal(agg.length, 1);
  assert.equal(agg[0].members.length, 10);
  assert.equal(r.nodes.length, 1 + 3 + 1);
  check(r, 'collapsed13');
  const ex = layoutGraph(all, { ...DEFAULT_LAYOUT, expanded: new Set(['b.root']) });
  assert.equal(ex.nodes.length, 14);
  check(ex, 'expanded13');
});

test('40 nodes in < 20 ms, synthetic wide + deep graph', () => {
  const lanes = DEFAULT_LAYOUT.lanes;
  const nodes = lanes.map((l) => ({ id: `${l}.root`, lane: l, parent: null, deps: [] }));
  for (let i = 0; nodes.length < 40; i++) {
    const lane = lanes[i % 3];
    const prev = nodes.filter((n) => n.lane === lane);
    nodes.push({
      id: `${lane}.${i}`,
      lane,
      parent: prev[Math.floor(prev.length / 2)].id,
      deps: i % 5 === 0 ? [nodes[nodes.length - 1].id] : [],
    });
  }
  const t0 = performance.now();
  const r = layoutGraph(nodes);
  const ms = performance.now() - t0;
  console.log(`TIMING synthetic40 nodes=40 drawn=${r.nodes.length} first=${ms.toFixed(3)}ms`);
  assert.ok(ms < 20);
  check(r, 'synthetic40');
});

test('a cycle does not hang or produce NaN', () => {
  const r = layoutGraph([
    { id: 'a', lane: 'builder', parent: 'b', deps: [] },
    { id: 'b', lane: 'builder', parent: 'a', deps: [] },
  ]);
  for (const n of r.nodes) assert.ok(Number.isFinite(n.y));
});
