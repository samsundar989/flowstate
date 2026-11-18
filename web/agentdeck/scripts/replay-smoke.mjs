// Fixture smoke test for src/replay.ts + src/gesture.ts + src/cues.ts (node >= 22.6):
//   node --experimental-strip-types scripts/replay-smoke.mjs
// Replays every agents/fixtures/*.jsonl on a fake clock at 64x in three modes:
//   auto     questions time out after 2 s and take the default option;
//   poke     each question is answered at once with the non-default option;
//   gesture  synthetic hand-joint frames (tests/hand_poses.json, captured from the emulator
//            hand) go through classifyThumb + ThumbGate. The simulated user forms the pose as
//            the card appears (must NOT fire: formed before arming), relaxes, shows a fist and
//            an open hand (must not fire), then holds thumbs-up / thumbs-down alternately per
//            question (alternating across fixtures, so both poses are exercised). Checks: answered by the user, >= ARM_MS + HOLD_MS after the card, the
//            option tagged with that gesture, exactly one answer per question.
// Fails on any exception, an unfinished run, an agent that does not end COMPLETED, a gesture
// check, a fixture missing from the in-app picker (src/deck.ts) or fewer than 7 fixtures.
import { readFileSync, readdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { cueFor } from '../src/cues.ts';
import { ARM_MS, HOLD_MS, ThumbGate, classifyThumb, optionFor, questionKey } from '../src/gesture.ts';
import { ReplayRuntime, parseJsonl, skipLabel } from '../src/replay.ts';
import { stationPosition, thumbsUpOption } from '../src/portfolio.ts';
import assert from 'node:assert/strict';
import { StandupSession } from '../src/standup.ts';
import { STATION_SCALE, STATION_W_UNITS } from '../src/theme.ts';

const here = dirname(fileURLToPath(import.meta.url));
const dir = join(here, '../../../agents/fixtures');
const POSES = JSON.parse(readFileSync(join(here, '../tests/hand_poses.json'), 'utf8'));
const deckSrc = readFileSync(join(here, '../src/deck.ts'), 'utf8');
const ROLES = ['researcher', 'builder', 'reviewer'];
let bad = 0;
let runs = 0;
let questionsSeen = 0; // alternates thumbs-up / thumbs-down across all fixtures
const fail = (msg) => {
  bad++;
  console.log(`FAIL ${msg}`);
};

const files = readdirSync(dir).filter((n) => n.endsWith('.jsonl'));
if (process.argv.includes('standup')) {
  const events = parseJsonl(readFileSync(join(dir, 'real_autoflow_day.jsonl'), 'utf8'));
  const rt = new ReplayRuntime(events, { speed: 30, autoAnswerS: 0, standup: true });
  const session = new StandupSession(0, true);
  session.advance(0);
  let now = 0, responses = 0;
  const order = [];
  rt.on(e => { if (e.type === 'event') { if (e.event.kind === 'input_response') responses++; else order.push(e.event); } });
  rt.tick(0);
  while (!rt.finished && now < 4_000_000) { now += 16; rt.tick(now); }
  assert.equal(rt.finished, true);
  assert.equal(rt.portfolio.queue.length, 0);
  assert.equal(responses, 15);
  assert.equal(rt.emitted, events.length);
  // Nothing dropped or reordered: same objects, recorded t_ms, chronological order.
  // (events at the start instant are applied by the constructor, before the listener attaches)
  const sorted = [...events].sort((a, b) => a.t_ms - b.t_ms);
  assert.deepEqual(order, sorted.slice(sorted.length - order.length));
  assert.equal(order.length, events.length - events.filter(e => e.t_ms === rt.startMs).length);
  assert.ok(session.complete(now) <= 3, `standup_minutes ${session.complete(now)} > 3`);
  console.log(`OK real_autoflow_day [standup] speed=30 events=${rt.emitted} answers=${responses} standup_minutes=${session.complete(now).toFixed(3)} recorded_span_minutes=${((rt.endMs - rt.startMs) / 60000).toFixed(3)} gaps_collapsed=${rt.skips.length} marked=${rt.markedSkips.length} longest="${rt.markedSkips.reduce((a, b) => b.toMs - b.fromMs > a.toMs - a.fromMs ? b : a, { fromMs: 0, toMs: 0 }).toMs ? skipLabel(rt.markedSkips.reduce((a, b) => b.toMs - b.fromMs > a.toMs - a.fromMs ? b : a)) : '-'}"`);
  process.exit(0);
}
if (files.length < 7) fail(`only ${files.length} fixtures`);
for (const f of files) {
  if (!deckSrc.includes(`'${f.replace('.jsonl', '')}'`)) fail(`${f} not in the deck.ts picker`);
}

/** Which synthetic hand the user shows `t` ms after the card appeared. */
function userHand(t, want) {
  if (t < 1100) return POSES[`thumbs-up_${want}`]; // formed before arming: must not count
  if (t < 1200) return POSES.default_up;
  if (t < 1400) return POSES.fist_up;
  if (t < 1600) return POSES.open_up;
  return POSES[`thumbs-up_${want}`];
}

for (const f of files) {
  for (const mode of ['auto', 'poke', 'gesture']) {
    runs++;
    const events = parseJsonl(readFileSync(join(dir, f), 'utf8'));
    if (events.some(e => e.project)) {
      try { portfolioRun(f, mode, events); } catch (e) { fail(`${f} [${mode}] ${e.stack}`); }
      continue;
    }
    const rt = new ReplayRuntime(events, {
      speed: 64,
      autoAnswerS: mode === 'gesture' ? 10 : 2,
    });
    const seen = new Set();
    const notes = [];
    const cues = {};
    const answers = []; // gesture mode: { key, at, want, opt }
    const asked = new Map(); // key -> { want, expected, shownAt }
    let now = 0;
    rt.on((e) => {
      if (e.type === 'state') {
        seen.add(`${e.agent.id}:${e.agent.status}`);
        if (e.agent.note && !notes.includes(e.agent.note)) notes.push(e.agent.note);
      } else if (e.type === 'event') {
        const c = cueFor(e.event);
        if (c) cues[c] = (cues[c] ?? 0) + 1;
        if (e.event.kind === 'input_response' && mode === 'gesture' && !/^You chose/u.test(e.event.title ?? ''))
          fail(`${f} [gesture] ${e.event.agent_id} answered by ${e.event.title}`);
      }
    });
    const gate = new ThumbGate();
    rt.tick(now);
    rt.assignTask('builder', 'Update the changelog', 'high');
    while (!rt.finished && now < 3_600_000) {
      now += 16;
      rt.tick(now);
      if (mode === 'poke') {
        for (const id of ROLES) {
          const q = rt.pending(id);
          if (q) rt.answer(id, q.options.find((o) => o.id !== q.default_option)?.id ?? q.default_option);
        }
      } else if (mode === 'gesture') {
        let target = null;
        for (const id of ROLES) {
          const q = rt.pending(id);
          if (q && (!target || q.askedAtReal < target.q.askedAtReal)) target = { id, q };
        }
        if (!target) {
          gate.update(classifyThumb(POSES.default_up), now, null);
          continue;
        }
        const key = questionKey(target.id, target.q);
        if (!asked.has(key)) {
          const want = questionsSeen++ % 2 === 0 ? 'up' : 'down';
          asked.set(key, { want, expected: optionFor(target.q, want).id, shownAt: target.q.askedAtReal });
        }
        const a = asked.get(key);
        const pose = classifyThumb(userHand(now - a.shownAt, a.want));
        const fired = gate.update(pose, now, { key, shownAtMs: a.shownAt });
        if (fired) {
          const opt = optionFor(target.q, fired).id;
          answers.push({ key, at: now - a.shownAt, want: a.want, fired, opt });
          rt.answer(target.id, opt, 'user');
        }
      }
    }
    // Gesture checks.
    let gestureLine = '';
    if (mode === 'gesture') {
      for (const [key, a] of asked) {
        const got = answers.filter((x) => x.key === key);
        if (got.length !== 1) fail(`${f} [gesture] ${key}: ${got.length} answers`);
        const g = got[0];
        if (!g) continue;
        if (g.fired !== a.want) fail(`${f} [gesture] ${key}: pose ${g.fired}, wanted ${a.want}`);
        if (g.opt !== a.expected) fail(`${f} [gesture] ${key}: answered ${g.opt}, wanted ${a.expected}`);
        if (g.at < ARM_MS + HOLD_MS) fail(`${f} [gesture] ${key}: answered ${g.at} ms after the card`);
      }
      gestureLine = asked.size === 0 ? '(fixture asks no question)' : answers.map((x) => `thumbs-${x.fired}->${x.opt}@${(x.at / 1000).toFixed(2)}s`).join(', ');
    }
    const final = [...rt.agents.values()].map((a) => `${a.id}=${a.status}`).join(' ');
    const ok = rt.finished && [...rt.agents.values()].every((a) => a.status === 'COMPLETED');
    if (!ok) bad++;
    console.log(
      `${ok ? 'OK  ' : 'FAIL'} ${f} [${mode}] events=${rt.emitted} real=${(now / 1000).toFixed(1)}s sim=${(rt.simTimeMs / 1000).toFixed(0)}s final: ${final}`,
    );
    console.log(`     states: ${[...seen].sort().join(', ')}`);
    console.log(`     cues: ${Object.entries(cues).map(([k, v]) => `${k}=${v}`).join(' ')}`);
    if (gestureLine) console.log(`     gestures: ${gestureLine}`);
    console.log(`     notes: ${notes.filter((n) => /chose|recording|Waiting|Error|task/u.test(n)).slice(0, 8).join(' | ')}`);
  }
}
console.log(`${runs} runs (${files.length} fixtures x 3 modes), ${bad} failures`);
process.exit(bad ? 1 : 0);

function portfolioRun(file, mode, events) {
  const rt = new ReplayRuntime(events, { speed: file === 'autoflow_sample.jsonl' ? 1 : 64, autoAnswerS: mode === 'auto' ? 2 : null });
  const p = rt.portfolio;
  const expected = new Set(events.map(e => e.project).filter(Boolean)); expected.add('chief');
  assert.deepEqual(new Set(p.stations.keys()), expected);
  const positions = [...p.stations.keys()].map((_, i) => stationPosition(i, p.stations.size));
  for (let i = 0; i < positions.length; i++) {
    assert.ok(Math.abs(Math.hypot(positions[i][0], positions[i][2]) - 1.1) < 1e-9);
    if (i) assert.ok(Math.hypot(...positions[i].map((x, k) => x - positions[i-1][k])) - STATION_W_UNITS * 0.005 * STATION_SCALE >= 0.012);
    assert.ok(Math.abs(Math.atan2(positions[i][0], positions[i][2])) * 180 / Math.PI <= 60, 'station outside +-60 deg');
  }
  for (const id of p.stations.keys()) { p.promote(id); assert.equal(p.promoted, id); p.promote(id); assert.equal(p.promoted, null); }
  const responses = [], asked = new Set(), snoozed = new Set();
  rt.on(e => { if (e.type === 'event' && e.event.kind === 'input_response') responses.push(e.event); });
  const gate = new ThumbGate();
  let now = 0;
  rt.tick(now);
  while (!rt.finished && now < 3_600_000) {
    now += 16; rt.tick(now);
    const q = p.showFront(now);
    if (!q) { gate.update(null, now, null); continue; }
    asked.add(q.decision_id);
    // Queue contract: blocking, then earliest deadline until explicitly snoozed.
    const unsnoozed = p.queue.filter(d => d.order === 0);
    if (q.order === 0 && unsnoozed.some(d => d.blocking)) assert.ok(q.blocking);
    assert.equal(rt.answerDecision(q.decision_id, 'invalid-option'), false);
    if (mode === 'poke') {
      const opt = q.options.find(o => o.id !== q.default_option) ?? q.options[0];
      assert.ok(rt.answerDecision(q.decision_id, opt.id));
    } else if (mode === 'gesture') {
      const want = snoozed.has(q.decision_id) ? 'up' : 'down';
      const pose = classifyThumb(userHand(now - q.shownAt, want));
      const fired = gate.update(pose, now, { key: `${q.decision_id}:${q.order}`, shownAtMs: q.shownAt });
      if (fired === 'down') {
        const before = responses.length;
        assert.ok(p.snooze(q.decision_id, now));
        snoozed.add(q.decision_id);
        assert.equal(p.queue[p.queue.length - 1].decision_id, q.decision_id);
        assert.equal(responses.length, before, 'snooze must not answer');
      } else if (fired === 'up') {
        assert.ok(now - q.shownAt >= ARM_MS + HOLD_MS);
        const thumb = thumbsUpOption(q);
        // A malformed recommendation: thumbs-up takes the marked default, else only a poke answers.
        if (thumb?.via !== 'recommendation') console.log(`     ${file} decision_id=${q.decision_id}: recommendation "${q.recommendation}" matches no option -> ${thumb ? `default ${thumb.id}` : 'poke'}`);
        assert.ok(rt.answerDecision(q.decision_id, thumb?.id ?? q.options[0].id));
      }
    }
  }
  const decisions = events.filter(e => e.kind === 'decision');
  assert.ok(rt.finished, 'fixture must finish');
  assert.equal(p.queue.length, 0);
  assert.equal(responses.length, new Set(decisions.map(d => d.decision_id)).size);
  assert.equal(new Set(responses.map(d => d.decision_id)).size, responses.length);
  for (const response of responses) {
    const d = decisions.find(d => d.decision_id === response.decision_id);
    assert.ok(d.options.some(o => o.id === response.answer));
    if (mode === 'gesture') { assert.equal(response.answer, thumbsUpOption(d)?.id ?? d.options[0].id); assert.match(response.title, /^You chose/); }
    assert.equal(cueFor(response), 'approve');
  }
  if (mode === 'gesture') assert.equal(p.snoozed, decisions.length);
  console.log(`OK   ${file} [${mode}] stations=${p.stations.size} decisions=${responses.length} answered=${p.answered} snoozed=${p.snoozed} inbox=0 emitted=${rt.emitted}`);
  console.log(`     input_response: ${responses.map(d => `decision_id=${d.decision_id} answer=${d.answer}`).join(', ')}`);
}
