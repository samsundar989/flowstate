import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const events = readFileSync(new URL('../src/recordings/real_autoflow_day.jsonl', import.meta.url), 'utf8').trim().split('\n').map(JSON.parse);

test('all recorded Web display text omits broken copy while preserving decision choices', () => {
  const text = JSON.stringify(events);
  for (const broken of ['model model', '[link]', '[path]', 'Overhand', 'done,later,question']) assert.equal(text.includes(broken), false, broken);
  const direction = events.find(e => e.kind === 'decision' && e.decision_id === '18');
  assert.match(direction.options[0].label, /Flowstate/);
  assert.equal(direction.recommendation, direction.options[0].label);
  const checklist = events.find(e => e.kind === 'decision' && e.decision_id === '9');
  assert.deepEqual(checklist.options.map(o => o.id), ['done', 'later', 'question']);
});
