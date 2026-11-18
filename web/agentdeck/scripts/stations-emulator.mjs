// Run from web/agentdeck with a command-ready SDK runtime.
// Uses the SDK CLI directly to avoid PowerShell's JSON quoting conversion.
import { spawnSync } from 'node:child_process';
import { writeFileSync } from 'node:fs';
const out = '../../runs/sim/';
const cli = (domain, action, input = {}, flags = []) => {
  const result = spawnSync(process.execPath, ['node_modules/@iwsdk/cli/bin/iwsdk.js', domain, action,
    '--input-json', JSON.stringify(input), ...flags], { encoding: 'utf8', timeout: 60000, maxBuffer: 16 * 1024 * 1024 });
  if (result.error) throw result.error;
  if (!result.stdout.trim()) throw new Error(`CLI ${domain} ${action} exit=${result.status}: ${result.stderr}`);
  const parsed = JSON.parse(result.stdout);
  if (!parsed.ok) throw new Error(JSON.stringify(parsed));
  return parsed.data.result ?? parsed.data;
};
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const save = (name, value) => writeFileSync(out + `web_stations_${name}.json`, JSON.stringify(value, null, 2));
const shot = name => cli('browser', 'screenshot', {}, ['--output-file', out + `web_stations_${name}.png`]);
const logsForLoad = () => {
  const logs = cli('browser', 'logs', { count: 80 });
  const start = logs.findLastIndex(l => l.message.includes('[IWSDK Dev] Configuration'));
  return logs.slice(Math.max(0, start));
};
const key = key => cli('browser', 'interact', { steps: [{ action: 'press', key }] });
const hand = (poseId, thumb = 'up', duration = 0.8) => cli('xr', 'run-sequence', {
  device: 'hand-right', steps: [{ position: { relativeTo: 'head', x: 0.22, y: -0.08, z: -0.30 },
    anchor: 'wrist', orientation: { fingers: 'forward', thumb }, poseId }, { duration }],
}, ['--timeout', '45000']);
const stage = process.argv[2] ?? 'targets';
if (stage === 'targets') {
  save('targets', cli('xr', 'list-targets'));
  save('entities', cli('ecs', 'find', { namePattern: 'Station|Decision Inbox', limit: 30 }));
  console.log('Saved target and entity state');
} else if (stage === 'answer') {
  cli('browser', 'reload'); await sleep(3500);
  cli('xr', 'enter'); cli('xr', 'set-input-mode', { mode: 'hand' });
  cli('xr', 'set-transform', { device: 'headset', position: { x: 0, y: 1.6, z: 0 } });
  cli('xr', 'look-at', { device: 'headset', target: { x: 0, y: 1.6, z: -1 } });
  key('r');
  hand('default', 'up', 0.2); await sleep(1000);
  save('session', cli('xr', 'status'));
  shot('decision');
  hand('thumbs-up', 'up', 1.0);
  shot('answered'); shot('inbox_zero');
  const logs = logsForLoad();
  save('console', logs);
  const response = logs.find(l => /input_response.*decision_id=12/u.test(l.message));
  if (!response || !logs.some(l => /gesture thumbs-up decision_id=12/u.test(l.message))) throw new Error('No synthetic thumb response');
  console.log(response.message);
  await sleep(6800); shot('arc');
  save('targets', cli('xr', 'list-targets'));
} else if (stage === 'promote') {
  // Focus the arc station at -12 degrees using an indirect hand ray, then pinch.
  const targets = cli('xr', 'list-targets');
  const button = targets.targets.find(t => t.name === 'Station Project 4/promote-button');
  if (!button) throw new Error('station button missing');
  if (button.size.y < 0.057) throw new Error('station target below 3 degree height at 1.10 m');
  cli('xr', 'look-at', { device: 'hand-right', target: button.position });
  cli('xr', 'select', { device: 'hand-right', duration: 0.2 });
  await sleep(800);
  shot('promoted');
  save('promoted_targets', cli('xr', 'list-targets'));
  cli('xr', 'run-sequence', { device: 'hand-right', steps: [{ target: 'Station Project 4/promote-button', press: {} }] }, ['--timeout', '45000']);
  const logs = logsForLoad();
  save('interaction_console', logs);
  if (!logs.some(l => /station .* promoted/u.test(l.message))) throw new Error('pinch did not promote');
  if (!logs.some(l => /station .* demoted/u.test(l.message))) throw new Error('poke did not demote');
  console.log('Pinch promoted; poke demoted');
} else if (stage === 'poke') {
  cli('browser', 'reload'); await sleep(3500);
  cli('xr', 'enter'); cli('xr', 'set-input-mode', { mode: 'hand' });
  await sleep(1000);
  cli('xr', 'run-sequence', { device: 'hand-right', steps: [{ target: 'Decision 12 option B/option-button', press: {} }] }, ['--timeout', '45000']);
  const logs = logsForLoad();
  save('poke_console', logs);
  if (!logs.some(l => /input_response.*You chose: B.*decision_id=12 answer=B/u.test(l.message))) throw new Error('Poke did not answer B');
  console.log('Poke answered decision_id=12 answer=B');
} else if (stage === 'profile' || stage === 'profile-legacy') {
  if (stage === 'profile-legacy') { key('n'); key('n'); await sleep(2000); }
  save('profile_start', cli('browser', 'profile', { action: 'start', mode: 'rendering', maxDurationMs: 10000 }));
  await sleep(10500);
  save(stage, cli('browser', 'profile', { action: 'stop' }));
  save(stage === 'profile' ? 'console' : 'legacy_console', logsForLoad());
  console.log('Saved rendering profile and console');
}
