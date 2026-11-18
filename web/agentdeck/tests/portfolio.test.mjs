import { test } from 'node:test';
import assert from 'node:assert/strict';
import { ReplayRuntime } from '../src/replay.ts';
import { Portfolio, recommendedOption } from '../src/portfolio.ts';
import { ThumbGate } from '../src/gesture.ts';

const decision = (id, more = {}) => ({ t_ms: 0, agent_id: 'head', project: 'one',
  kind: 'decision', decision_id: id, status: 'INPUT_REQUIRED',
  options: [{ id: 'A', label: 'A: Ship' }, { id: 'B', label: 'B: Revise' }],
  default_option: 'B', recommendation: 'A', ...more });

test('blocking priority, deadline order, snooze to back, no answer on snooze', () => {
  const events = [decision('late', { deadline_ms: 90000 }), decision('early', { deadline_ms: 80000 }),
    decision('blocking', { blocking: true })];
  const rt = new ReplayRuntime(events, { speed: 1, autoAnswerS: null });
  const responses = [];
  rt.on(e => { if (e.type === 'event' && e.event.kind === 'input_response') responses.push(e.event); });
  rt.tick(0);
  assert.deepEqual(rt.portfolio.queue.map(d => d.decision_id), ['blocking', 'early', 'late']);
  rt.portfolio.snooze('blocking', 100);
  assert.deepEqual(rt.portfolio.queue.map(d => d.decision_id), ['early', 'late', 'blocking']);
  assert.equal(responses.length, 0);
  assert.equal(rt.answerDecision('early', 'invalid'), false);
  assert.equal(rt.answerDecision('early', 'A'), true);
  assert.equal(rt.answerDecision('early', 'B'), false);
  assert.equal(responses.length, 1);
  assert.equal(responses[0].decision_id, 'early');
});

test('a held thumb cannot carry into the next front card or a snoozed card', () => {
  const p = new Portfolio([decision('1'), decision('2')]);
  p.apply(decision('1'), 0); p.apply(decision('2'), 0);
  const gate = new ThumbGate();
  const target = now => { const d = p.showFront(now); return { key: `${d.decision_id}:${d.order}`, shownAtMs: d.shownAt }; };
  gate.update(null, 0, target(0));
  gate.update('up', 1001, target(1001));
  assert.equal(gate.update('up', 1501, target(1501)), 'up');
  p.decisions.delete('1');
  assert.equal(gate.update('up', 1600, target(1600)), null);
  assert.equal(gate.update('up', 3200, target(3200)), null);
  gate.update(null, 3300, target(3300));
  gate.update('down', 3400, target(3400));
  assert.equal(gate.update('down', 3900, target(3900)), 'down');
  p.snooze('2', 3900);
  assert.equal(gate.update('down', 5500, target(5500)), null);
});

test('only unambiguous recommendation labels and ID prefixes resolve', () => {
  assert.equal(recommendedOption(decision('1', { recommendation: 'A: Ship' })), 'A');
  assert.equal(recommendedOption(decision('1', { recommendation: 'A. The checks passed' })), 'A');
  assert.equal(recommendedOption(decision('1', { recommendation: 'done' })), null);
});

test('shared source agent IDs retain independent project states', () => {
  const p = new Portfolio([decision('1'), decision('2', { project: 'two' })]);
  p.apply({ project: 'one', agent_id: 'head', kind: 'status', status: 'FAILED' }, 0);
  p.apply({ project: 'two', agent_id: 'head', kind: 'status', status: 'WORKING' }, 0);
  assert.equal(p.stations.get('one').rows.get('head').status, 'FAILED');
  assert.equal(p.stations.get('two').rows.get('head').status, 'WORKING');
});

test('recorded completion cannot silently remove a decision; explicit deadline takes default once', () => {
  const rt = new ReplayRuntime([decision('1', { deadline_ms: 60000 }),
    { ...decision('1'), kind: 'status', status: 'COMPLETED', t_ms: 1000 }], { speed: 1, autoAnswerS: null });
  const responses = [];
  rt.on(e => { if (e.type === 'event' && e.event.kind === 'input_response') responses.push(e.event); });
  rt.tick(0); rt.tick(1000);
  assert.equal(rt.portfolio.queue.length, 1);
  assert.equal(rt.finished, false);
  rt.tick(60000); rt.tick(61000);
  assert.equal(responses.length, 1);
  assert.equal(responses[0].answer, 'B');
  assert.equal(rt.finished, true);
});

test('gesture: a malformed recommendation (decision 9) takes the marked default by thumbs-up, else nothing; poke stays live; thumbs-down snoozes', async () => {
  const { thumbsUpOption } = await import('../src/portfolio.ts');
  const nine = decision('9', { recommendation: 'done', options: [{ id: '1', label: '1' }], default_option: '1' });
  const bare = decision('10', { recommendation: 'done', default_option: undefined });
  const rt = new ReplayRuntime([nine, { ...bare, t_ms: 1 }], { speed: 1, autoAnswerS: null });
  const responses = [];
  rt.on(e => { if (e.type === 'event' && e.event.kind === 'input_response') responses.push(e.event); });
  rt.tick(0); rt.tick(1);
  const p = rt.portfolio;
  assert.equal(recommendedOption(p.decisions.get('9')), null);
  assert.deepEqual(thumbsUpOption(p.decisions.get('9')), { id: '1', via: 'default' });
  // No marked default: the runtime's auto-answer fallback (first option) must not leak into the thumb.
  assert.equal(p.decisions.get('10').default_option, 'A');
  assert.equal(thumbsUpOption(p.decisions.get('10')), null);
  // Hold thumbs-up on each front card, routed as panels.ts does.
  const gate = new ThumbGate();
  const hold = (pose, from) => {
    const d = p.showFront(from);
    const target = { key: `${d.decision_id}:${d.order}`, shownAtMs: d.shownAt };
    gate.update(null, from + 1000, target); gate.update(pose, from + 1001, target);
    const fired = gate.update(pose, from + 1501, target);
    if (fired === 'down') rt.snoozeDecision(d.decision_id, from + 1501);
    else if (fired === 'up') { const o = thumbsUpOption(d); if (o) rt.answerDecision(d.decision_id, o.id); else gate.release(target.key); }
    return fired;
  };
  assert.equal(p.queue[0].decision_id, '9');
  assert.equal(hold('up', 0), 'up');
  assert.deepEqual(responses.map(r => [r.decision_id, r.answer]), [['9', '1']]);
  assert.equal(hold('up', 2000), 'up');
  assert.equal(responses.length, 1, 'thumbs-up on a card with no usable option answers nothing');
  assert.equal(p.queue[0].decision_id, '10');
  // A still-held thumb does not re-fire on the released card.
  assert.equal(gate.update('up', 4000, { key: '10:0', shownAtMs: 2000 }), null);
  assert.equal(hold('down', 4000), 'down');
  assert.equal(p.snoozed, 1); assert.equal(responses.length, 1);
  assert.ok(rt.answerDecision('10', 'B'), 'the poke path still answers');
  assert.equal(p.queue.length, 0);
});
