// node --test --experimental-strip-types --no-warnings tests/seated_stations.test.mjs
// Task #469: who owns the near slot, and that full station text gets the room it wraps to.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { cardCentreY, decisionBackdrop, gateLabel, guidedStage, Portfolio, projectPositionLabel, returnLabel, stationHeightUnits, stationInspecting, steppedStation, wrappedLines, paginateText } from '../src/portfolio.ts';

const CARD = 'choose:2:-0.5';

test('a station brought over the open card takes the near slot', () => {
  assert.equal(stationInspecting('chief', CARD, CARD, true, 'replay'), true);
});
test('a new question takes the near slot back', () => {
  assert.equal(stationInspecting('chief', CARD, 'choose:1:0', true, 'replay'), false);
});
test('with nothing promoted the card keeps the slot', () => {
  assert.equal(stationInspecting(null, CARD, CARD, true, 'replay'), false);
});
test('with no card up, ordinary promotion needs no arbitration', () => {
  assert.equal(stationInspecting('chief', CARD, CARD, false, 'replay'), false);
});
test('replay complete can inspect a station', () => {
  assert.equal(stationInspecting('chief', 'zero:complete', 'zero:complete', true, 'complete'), true);
});
for (const stage of ['summary', 'station', 'scrub', 'thumbs']) {
  test(`guided ${stage} keeps its card`, () => assert.equal(stationInspecting('chief', stage, stage, true, stage), false));
}

test('promotion toggles and rejects unknown stations', () => {
  const p = new Portfolio([{ kind: 'status', project: 'a', agent_id: 'x', t_ms: 0 }]);
  p.promote('a'); assert.equal(p.promoted, 'a');
  p.promote('missing'); assert.equal(p.promoted, 'a');
  p.promote('a'); assert.equal(p.promoted, null);
});

test('gate text is never cut, and the fallback is not double-labelled', () => {
  assert.equal(gateLabel('Next gate not supplied'), 'NEXT GATE NOT SUPPLIED');
  assert.equal(gateLabel('Portfolio overview'), 'GATE PORTFOLIO OVERVIEW');
  assert.equal(gateLabel(''), '', 'no recorded gate shows no line');
  assert.equal(gateLabel('', ' 3D'), '');
  const long = 'Quest three worn comfort review with an unfamiliar tester';
  assert.equal(gateLabel(long, ' 9D'), `GATE ${long.toUpperCase()} 9D`);
});

test('word wrap counts lines, including a word longer than a line', () => {
  assert.equal(wrappedLines('', 21), 1);
  assert.equal(wrappedLines('NEXT GATE NOT SUPPLIED', 21), 2);
  assert.equal(wrappedLines('A'.repeat(50), 21), 3);
});

// The longest labels the recorded day can show, then adversarial lengths.
const WORST = [
  ['Circuit compilation', 'NEXT GATE NOT SUPPLIED', ['HEAD       NEEDS YOU']],
  ['Headset competition', 'NEXT GATE NOT SUPPLIED', ['HEAD       NEEDS YOU', 'OPERATOR   QUEUED', 'WORKER     DONE']],
  ['Molecule discovery', 'GATE PORTFOLIO OVERVIEW 12D', ['OPERATOR   QUEUED']],
  ['An unusually long recorded project name here', gateLabel('Quest three worn comfort review with an unfamiliar tester', ' 9D'), ['A VERY LONG AGENT NAME INDEED NEEDS YOU']],
  ['Pneumonoultramicroscopicsilicovolcanoconiosis', gateLabel('X'.repeat(70)), Array(6).fill('WORKERWITHLONGNAME CANCELED')],
];
for (const [name, gate, rows] of WORST) {
  test(`room for every wrapped line: ${name.slice(0, 24)}`, () => {
    const oneLine = stationHeightUnits('Name', 'GATE X', rows.map(() => 'HEAD DONE'));
    const extra = (wrappedLines(name, 15) - 1) * 145 + (wrappedLines(gate, 21) - 1) * 96
      + rows.reduce((n, r) => n + wrappedLines(r, 21) - 1, 0) * 96;
    assert.equal(stationHeightUnits(name, gate, rows), oneLine + extra);
  });
}
test('one-line stations keep their original height', () => {
  assert.equal(stationHeightUnits('Circuit design', 'GATE X', ['HEAD WORKING']), 1180 + 110);
});

// Task #473: the near project column visits every station in arc order and names where it returns.
const IDS = ['a', 'b', 'c'];
test('stepping forward from the first visits every station and wraps', () => {
  const seen = []; let at = 'a';
  for (let i = 0; i < IDS.length; i++) { seen.push(at); at = steppedStation(IDS, at, 1); }
  assert.deepEqual(seen, IDS); assert.equal(at, 'a');
});
test('previous wraps from the first to the last', () => assert.equal(steppedStation(IDS, 'a', -1), 'c'));
test('stepping with an unknown or absent current starts at the first', () => {
  assert.equal(steppedStation(IDS, 'gone', 1), 'a');
  assert.equal(steppedStation(IDS, null, -1), 'a');
});
test('no stations gives nothing, one station stays put', () => {
  assert.equal(steppedStation([], 'a', 1), null);
  assert.equal(steppedStation(['a'], 'a', 1), 'a');
});
test('stepping over an open card keeps the card key, so a station still owns the near slot', () => {
  const p = new Portfolio([{ kind: 'status', project: 'a', agent_id: 'x', t_ms: 0 }, { kind: 'status', project: 'b', agent_id: 'y', t_ms: 0 }]);
  p.promote('a'); p.promote(steppedStation([...p.stations.keys()], p.promoted, 1));
  assert.equal(p.promoted, 'b');
  assert.equal(stationInspecting(p.promoted, CARD, CARD, true, 'replay'), true);
});
test('position label names the project and its place, including duplicate names', () => {
  assert.equal(projectPositionLabel(2, 8, 'Circuit compilation'), 'PROJECT 3 OF 8 / CIRCUIT COMPILATION');
  assert.notEqual(projectPositionLabel(0, 2, 'Same'), projectPositionLabel(1, 2, 'Same'));
});
test('return label names the destination for each view', () => {
  assert.equal(returnLabel(true, CARD), 'BACK TO DECISION');
  assert.equal(returnLabel(true, 'receipt:r1'), 'BACK TO RECEIPT');
  assert.equal(returnLabel(true, 'zero:complete'), 'BACK TO SUMMARY');
  assert.equal(returnLabel(false, CARD), 'BACK TO ARC');
});
test('guided stages hide the near column', () => {
  for (const s of ['summary', 'station', 'scrub', 'thumbs']) assert.equal(guidedStage(s), true);
  for (const s of ['replay', 'complete', undefined]) assert.equal(guidedStage(s), false);
});

// Task #488: a scrubber press whose release is lost off the bar left `dragging` true, so the next hand pass sought the replay.
test('the scrubber drag ends when the pointer leaves the bar', async () => {
  const { readFileSync } = await import('node:fs');
  const src = readFileSync(new URL('../src/tray.ts', import.meta.url), 'utf8');
  assert.match(src, /bar\.addEventListener\('pointerleave', cancel\)/);
  assert.match(src, /bar\.removeEventListener\('pointerleave', cancel\)/);
});

// Task #492: a tall card rises clear of the options; the plate covers the column at its own depth.
test('a short card keeps its place', () => assert.equal(cardCentreY(0.08, -0.02, 0.15), 0.08));
test('a tall card rises so its bottom stays above the options', () => {
  const y = cardCentreY(0.08, -0.02, 0.26);
  assert.ok(y - 0.13 >= -0.02 + 0.008 - 1e-9 && y > 0.08);
});
test('the backdrop covers what the near column subtends', () => {
  const p = decisionBackdrop({ halfWidth: 0.16, top: 0.2, bottom: -0.256, near: 0.44, z: 0.47 });
  const lowZ = Math.sqrt(0.44 ** 2 - 0.256 ** 2);
  assert.ok(p.width / 2 / 0.47 >= 0.16 / lowZ, 'as wide as the nearest row subtends');
  assert.ok(p.y - p.height / 2 <= -0.256 / lowZ * 0.47 && p.y + p.height / 2 >= 0.2 / Math.sqrt(0.44 ** 2 - 0.04) * 0.47);
});

// Task #494: long decision text is paged, whole and in order, within a fixed card height.
const blocks = (question, evidence, reserve) => [
  { id: 'q', text: question, perLine: 40, lineUnits: 62.4, split: true },
  { id: 'r', text: 'Agent recommends: A', perLine: 48, lineUnits: 50.4, boxUnits: 34 },
  { id: 'd', text: 'CHOOSE ONE BELOW', perLine: 52, lineUnits: 43.2, reserve },
  { id: 'e', text: evidence, perLine: 54, lineUnits: 48, split: true },
  { id: 'h', text: 'HINT', perLine: 55, lineUnits: 42.5 },
];
const heightOf = (page, src) => page.reduce((n, b, i) => {
  const s = src.find(x => x.id === b.id);
  return n + (i ? 16 : 0) + wrappedLines(b.text, s.perLine) * s.lineUnits + (s.boxUnits ?? 0);
}, 0);
const rebuilt = (pages, id) => pages.flat().filter(b => b.id === id).map(b => b.text).join('');
test('short text stays on one page', () => assert.equal(paginateText(blocks('Which?', 'Because.'), 500).length, 1));
for (const [name, q, e] of [
  ['long question', 'word '.repeat(90).trim(), 'evidence here'],
  ['long evidence', 'A short question?', 'proof '.repeat(120).trim()],
  ['one huge word', 'X'.repeat(900), 'Y'.repeat(700)],
]) {
  test(`every character lands on a page within budget: ${name}`, () => {
    const b = blocks(q, e), pages = paginateText(b, 500);
    assert.ok(pages.length > 1);
    assert.equal(rebuilt(pages, 'q').replace(/ /g, ''), q.replace(/ /g, ''));
    assert.equal(rebuilt(pages, 'e').replace(/ /g, ''), e.replace(/ /g, ''));
    for (const page of pages) assert.ok(heightOf(page, b) <= 500 + 1e-6);
  });
}
test('recorded line breaks are counted and kept across pages', () => {
  assert.equal(wrappedLines('a\nb\n\nc', 40), 4);
  const e = Array.from({ length: 40 }, (_, i) => `line ${i}`).join('\n');
  const b = blocks('Q?', e), pages = paginateText(b, 500);
  assert.equal(rebuilt(pages, 'e').replace(/\s/g, ''), e.replace(/\s/g, ''));
  for (const page of pages) assert.ok(heightOf(page, b) <= 500 + 1e-6);
});
test('room is reserved so a selection cannot move a page break', () => {
  const q = 'word '.repeat(40).trim();
  const long = 'YOUR CHOICE: ' + 'L '.repeat(120);
  const pages = paginateText(blocks(q, 'e', long), 500);
  const withChoice = pages.map(p => p.map(b => b.id === 'd' ? { ...b, text: long } : b));
  for (const page of withChoice) assert.ok(heightOf(page, blocks(q, 'e')) <= 500 + 1e-6);
});

// Task #693: rows are worded from the station's one state, one row per role.
test('an open question: only the asking role needs you, no row says waiting or working', async () => {
  const { stationRows, stationState } = await import('../src/portfolio.ts');
  const agents = [{ name: 'Chief', status: 'SUBMITTED' }, { name: 'Chief', status: 'INPUT_REQUIRED' }];
  assert.equal(stationState(agents, 1, ''), 'INPUT_REQUIRED');
  assert.deepEqual(stationRows(agents, 'INPUT_REQUIRED'), ['Chief: needs you']);
  const team = [{ name: 'Worker', status: 'SUBMITTED' }, { name: 'Head', status: 'INPUT_REQUIRED' }, { name: 'Head', status: 'SUBMITTED' }, { name: 'Worker', status: 'SUBMITTED' }, { name: 'Operator', status: 'COMPLETED' }];
  assert.deepEqual(stationRows(team, 'INPUT_REQUIRED'), ['Head: needs you', 'Worker: on hold', 'Operator: done']);
});
test('a working station has no waiting rows; a stalled one has its live roles on hold', async () => {
  const { stationRows, stationState } = await import('../src/portfolio.ts');
  const queued = [{ name: 'Head', status: 'SUBMITTED' }, { name: 'Head', status: 'SUBMITTED' }];
  assert.equal(stationState(queued, 0, ''), 'WORKING');
  assert.deepEqual(stationRows(queued, 'WORKING'), ['Head: working']);
  const failed = [{ name: 'Head', status: 'SUBMITTED' }, { name: 'Worker', status: 'FAILED' }];
  assert.equal(stationState(failed, 0, ''), 'FAILED');
  assert.deepEqual(stationRows(failed, 'FAILED'), ['Head: on hold', 'Worker: stalled']);
  assert.equal(stationState([], 0, 'stall 2'), 'FAILED');
});
