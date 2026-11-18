// Fresh, reproducible acceptance checks; run from web/agentdeck on Node 22+.
import { spawnSync } from 'node:child_process';
import { readFileSync, writeFileSync, readdirSync, statSync, existsSync } from 'node:fs';
import assert from 'node:assert/strict';
import { join } from 'node:path';
const out = '../../runs/sim/web_standup_';
function run(name, command, args, production = false) {
  const r = spawnSync(command, args, { encoding: 'utf8', shell: command.endsWith('.cmd'),
    env: { ...process.env, ...(production ? { NODE_ENV: 'production' } : {}) }, maxBuffer: 32 * 1024 * 1024 });
  writeFileSync(out + name + '.log', r.stdout + r.stderr);
  assert.equal(r.status, 0, `${name}: ${r.stderr || r.stdout}`);
  return r.stdout;
}
run('typecheck', 'npm.cmd', ['run', 'typecheck']);
const tests = run('tests', 'npm.cmd', ['test']);
const smoke = run('smoke', process.execPath, ['--experimental-strip-types', '--no-warnings', 'scripts/replay-smoke.mjs']);
const standup = run('headless', process.execPath, ['--experimental-strip-types', '--no-warnings', 'scripts/replay-smoke.mjs', 'standup']);
run('build', 'npm.cmd', ['run', 'build'], true);
function size(path) {
  return readdirSync(path).reduce((sum, file) => {
    const p = join(path, file), s = statSync(p); return sum + (s.isDirectory() ? size(p) : s.size);
  }, 0);
}
const bytes = size('dist'); assert.ok(bytes < 5_000_000, `dist is ${bytes} bytes`);
const stills = ['summary', 'replay_30x', 'scrub', 'tutorial_thumbs', 'inbox_zero'];
for (const name of stills) assert.ok(existsSync(out + name + '.png'), `Missing ${name} still`);
function latestLoad(path) {
  const logs = JSON.parse(readFileSync(path, 'utf8'));
  const i = logs.findLastIndex(e => e.message.includes('[IWSDK Dev] Configuration'));
  return logs.slice(Math.max(0, i));
}
const tutorial = latestLoad(out + 'console_tutorial_final.json');
assert.ok(tutorial.some(e => e.message.includes('tutorial pinch-drag complete')));
assert.ok(tutorial.some(e => e.message.includes('gesture thumbs-up decision_id=tutorial')));
assert.ok(tutorial.some(e => e.message.includes('inbox zero: answered=15')));
const reload = latestLoad(out + 'console_reload.json');
const start = latestLoad(out + 'console_start.json');
assert.ok(start.some(e => e.message.includes('standup scrub last_seen=1779800')), 'Replay tray did not rewind');
const resume = reload.find(e => /standup resume last_seen=.*tutorial=skipped/u.test(e.message));
assert.ok(resume, 'No persisted second-launch evidence');
const errors = [...tutorial, ...reload, ...start].filter(e => e.level === 'error');
assert.equal(errors.length, 0, 'Current-load console errors');
const profile = JSON.parse(readFileSync(out + 'profile.json', 'utf8')).summary.frameTimeMs;
const result = { tests_passed: Number(/# pass (\d+)/u.exec(tests)[1]), smoke: smoke.trim().split('\n').pop(),
  standup_minutes: Number(/standup_minutes=([\d.]+)/u.exec(standup)[1]), dist_bytes: bytes,
  console_errors: errors.length, resume: resume.message, stills,
  emulator_p50_ms: profile.p50, emulator_p95_ms: profile.p95, calibrated: false };
writeFileSync(out + 'acceptance.json', JSON.stringify(result, null, 2));
console.log(JSON.stringify(result, null, 2));
