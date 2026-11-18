// Emulator stills + ergonomics dump for task #203 (run from web/agentdeck with the IWSDK dev
// server up; works unchanged against af/t112 for the before/after composite).
//   node scripts/integrated-shoot.mjs <prefix> [outDir]      e.g. integrated ../../runs/design
//   node scripts/integrated-shoot.mjs <prefix> <outDir> - profile    (10 s rendering profile only)
// Head at the XR origin (0, 1.6, 0) facing -Z, as runs/design/shoot.sh (#110). Real morning
// fixture at 30x. Ten stills: standup_{summary,station,scrub,thumbs,replay_30x} and
// stations_{arc,decision,answered,inbox_zero,promoted}. Writes <prefix>_targets_*.json (the
// emulator target dump) and <prefix>_ergonomics.json: eye distance of every poke target and the
// clear gap between neighbouring tray / option targets, measured from that dump.
import { spawnSync } from 'node:child_process';
import { writeFileSync } from 'node:fs';

const [prefix = 'integrated', outDir = '../../runs/design'] = process.argv.slice(2);
const cli = (domain, action, input = {}, flags = []) => {
  const r = spawnSync(process.execPath, ['node_modules/@iwsdk/cli/bin/iwsdk.js', domain, action,
    '--input-json', JSON.stringify(input), ...flags], { encoding: 'utf8', timeout: 90000, maxBuffer: 32 * 1024 * 1024 });
  if (r.error) throw r.error;
  if (!r.stdout.trim()) throw Error(`${domain} ${action} exit=${r.status}: ${r.stderr}`);
  const parsed = JSON.parse(r.stdout);
  if (!parsed.ok) throw Error(`${domain} ${action}: ${JSON.stringify(parsed).slice(0, 600)}`);
  return parsed.data.result ?? parsed.data;
};
const sleep = (ms) => new Promise((res) => setTimeout(res, ms));
const out = (name) => `${outDir}/${prefix}_${name}`;
const save = (name, v) => writeFileSync(out(`${name}.json`), JSON.stringify(v, null, 2));
const shot = (name) => { cli('browser', 'screenshot', {}, ['--output-file', out(`${name}.png`)]); console.log(`shot ${name}`); };
// run-sequence turns the head and leans the body toward each target: put the headset back on
// the origin before every framing so all stills share one viewpoint.
const look = async (x, y, z) => {
  const dx = x, dy = y - 1.6, dz = z;
  const yaw = Math.atan2(-dx, -dz) * 180 / Math.PI, pitch = Math.atan2(dy, Math.hypot(dx, dz)) * 180 / Math.PI;
  cli('xr', 'set-transform', { device: 'headset', position: { x: 0, y: 1.6, z: 0 }, orientation: { pitch, yaw, roll: 0 } });
  await sleep(1200);
};
const forward = () => look(0, 1.6, -1);
const logs = () => cli('browser', 'logs', { count: 200 }).map((l) => l.message ?? String(l));
const waitLog = async (re, s = 60) => {
  for (let i = 0; i < s; i++) { if (logs().some((m) => re.test(m))) return true; await sleep(1000); }
  console.log(`timeout waiting for ${re}`); return false;
};
const targets = () => cli('xr', 'list-targets').targets;
/** Current stand-up stage from the app log ("standup ... stage=<x>"). */
const stage = () => logs().map((m) => /standup .*stage=(\w+)/u.exec(m)?.[1]).filter(Boolean).pop();
const waitFor = async (fn, what, s = 30) => {
  for (let i = 0; i < s; i++) { const v = fn(); if (v) return v; await sleep(1000); }
  throw Error(`timeout waiting for ${what}`);
};
const recentre = () => cli('browser', 'interact', { steps: [{ action: 'press', key: 'r' }] });
const isOption = (t) => /^Decision .* option [^/]+$/u.test(t.name) && t.position.y > 0;
const press = (target) => cli('xr', 'run-sequence', { device: 'hand-right', steps: [{ target, press: {} }] }, ['--timeout', '45000']);
const rest = () => cli('xr', 'run-sequence', { device: 'hand-right', steps: [
  { position: { relativeTo: 'head', x: 0.25, y: -0.45, z: -0.1 }, anchor: 'wrist', poseId: 'default' }, { duration: 0.2 }] }, ['--timeout', '45000']);
const thumbs = (poseId, duration) => cli('xr', 'run-sequence', { device: 'hand-right', steps: [
  { position: { relativeTo: 'head', x: 0.22, y: -0.08, z: -0.30 }, anchor: 'wrist', orientation: { fingers: 'forward', thumb: 'up' }, poseId }, { duration }] }, ['--timeout', '45000']);
const drag = (from, toX) => {
  cli('xr', 'set-transform', { device: 'hand-right', position: { x: 0.18, y: 1.3, z: 0.1 } });
  cli('xr', 'look-at', { device: 'hand-right', target: from });
  cli('xr', 'set-select-value', { device: 'hand-right', value: 1 });
  cli('xr', 'look-at', { device: 'hand-right', target: { ...from, x: toX } });
};
const release = () => cli('xr', 'set-select-value', { device: 'hand-right', value: 0 });
const stationButtons = () => targets().filter((t) => /^Station .*\/promote-button$/u.test(t.name));
const centre = (list) => list[Math.floor((list.length - 1) / 2)];

// ---- ergonomics from a target dump --------------------------------------------------------
const EYE = { x: 0, y: 1.6, z: 0 };
const dist = (t) => Math.hypot(t.position.x - EYE.x, t.position.y - EYE.y, t.position.z - EYE.z);
/** Clear gap between two world AABBs (0 when they overlap). */
const gap = (a, b) => Math.hypot(...['x', 'y', 'z'].map((k) =>
  Math.max(0, Math.abs(a.position[k] - b.position[k]) - (a.size[k] + b.size[k]) / 2)));
const TRAY = ['replay-night', 'tutorial-button', 'skip-beat', 'scrubber', 'recentre-button', 'exit-button', 'scenario-button', 'speed-button', 'mute-button'];
function ergonomics(list, label) {
  const by = (n) => list.find((t) => t.name === n);
  const visible = (t) => t && t.size.x > 1e-4 && t.position.y > 0;
  const tray = TRAY.map(by).filter(visible);
  const options = list.filter((t) => /^Decision .* option [^/]+$/u.test(t.name) && visible(t));
  const promoted = list.filter((t) => /^Station .*\/promote-button$/u.test(t.name) && dist(t) < 0.6);
  const poke = [...tray, ...options, ...promoted].map((t) => ({ name: t.name, distance_m: +dist(t).toFixed(4) }));
  const pairs = [];
  for (const group of [tray, options]) for (let i = 0; i < group.length; i++) for (let j = i + 1; j < group.length; j++) {
    pairs.push({ a: group[i].name, b: group[j].name, gap_mm: +(gap(group[i], group[j]) * 1000).toFixed(1) });
  }
  // Neighbours = for each target, its nearest other target in the same group.
  const nearest = new Map();
  for (const p of pairs) for (const k of [p.a, p.b]) if (!nearest.has(k) || nearest.get(k).gap_mm > p.gap_mm) nearest.set(k, p);
  const neighbourGaps = [...new Set(nearest.values())];
  const ds = poke.map((p) => p.distance_m);
  return {
    label, eye: EYE, poke_targets: poke,
    distance_min_m: Math.min(...ds), distance_max_m: Math.max(...ds),
    in_band_042_046: poke.every((p) => p.distance_m >= 0.42 && p.distance_m <= 0.46),
    replay_night_m: poke.find((p) => p.name === 'replay-night')?.distance_m ?? null,
    neighbour_gaps: neighbourGaps, min_gap_mm: Math.min(...neighbourGaps.map((p) => p.gap_mm)),
  };
}

// ---- sequence -------------------------------------------------------------------------------
if (process.argv.includes('profile')) {
  // Regression-only frame timing: fresh load, in XR, facing the arc, 10 s rendering profile.
  try { cli('xr', 'exit'); } catch { /* not in XR */ }
  spawnSync(process.execPath, ['node_modules/@iwsdk/cli/bin/iwsdk.js', 'browser', 'run', 'scripts/clear-storage.mjs'], { encoding: 'utf8', timeout: 60000 });
  cli('browser', 'reload'); await sleep(1500);
  await waitLog(/loaded real_autoflow_day/u);
  cli('xr', 'enter'); cli('xr', 'set-input-mode', { mode: 'hand' });
  await forward(); await sleep(4000);
  cli('browser', 'profile', { action: 'start', mode: 'rendering', maxDurationMs: 10000 });
  await sleep(10500);
  save('profile', cli('browser', 'profile', { action: 'stop' }));
  console.log('profile saved'); process.exit(0);
}
try { cli('xr', 'exit'); } catch { /* not in XR */ }
// Fresh first visit (as #113): clear localStorage and block the old page's writes, then reload.
spawnSync(process.execPath, ['node_modules/@iwsdk/cli/bin/iwsdk.js', 'browser', 'run', 'scripts/clear-storage.mjs'], { encoding: 'utf8', timeout: 60000 });
cli('browser', 'reload'); await sleep(1500);
await waitLog(/loaded real_autoflow_day/u);
cli('xr', 'enter'); cli('xr', 'set-input-mode', { mode: 'hand' });
cli('xr', 'set-transform', { device: 'headset', position: { x: 0, y: 1.6, z: 0 } });
await forward();
await waitLog(/recentred at eye \(0\.00, 1\.60, 0\.00\)/u, 20);
await sleep(1500);
rest();

press('tutorial-button'); await look(0, 1.5, -0.44);
shot('standup_summary');
// The summary auto-advances to the station beat after 5 s (a skip press would race it).
await waitFor(() => stage() === 'station', 'stage=station');
rest(); await look(0, 1.55, -0.8);
shot('standup_station');
const station = centre(stationButtons());
const angle = Math.atan2(station.position.x, -station.position.z) * 180 / Math.PI;
console.log(`guided beat target ${station.name} at ${angle.toFixed(1)} deg`);
save('targets_station_beat', { promoted_target: station.name, promoted_angle_deg: +angle.toFixed(2), targets: targets() });
press(station.name); rest();
const knob = targets().find((t) => t.name === 'scrub-knob');
await look(0, 1.35, -0.35);
drag(knob.position, knob.position.x + 0.14);
shot('standup_scrub');
release(); rest(); await sleep(1200);
await look(0, 1.55, -0.44);
shot('standup_thumbs');
thumbs('thumbs-up', 1.0); rest(); await sleep(2000);
await look(0, 1.45, -0.6);
shot('standup_replay_30x');
await forward(); await sleep(800);
await look(0, 1.52, -1.1);
shot('stations_arc');

await waitLog(/decision_id=\S+ answer=-|DECISION HOLD|status INPUT_REQUIRED/u, 90);
await sleep(1500);
await look(0, 1.5, -0.44);
shot('stations_decision');
const decisionDump = targets();
save('targets_decision', decisionDump);
const ergoDecision = ergonomics(decisionDump, 'first decision open (tray + options)');
thumbs('default', 0.2); await sleep(1100); thumbs('thumbs-up', 1.0); rest(); await sleep(1500);
shot('stations_answered');

// Seek to the end of the night, then poke every remaining option until inbox zero.
const bar = targets().find((t) => t.name === 'scrubber');
const k2 = targets().find((t) => t.name === 'scrub-knob');
drag(k2.position, bar.position.x + bar.size.x / 2 - 0.002); release(); rest();
await forward(); await sleep(2500); recentre(); await sleep(1000);
await waitFor(() => targets().some(isOption), 'decision options after seek');
// Poke every open option until the replay finishes (the last asks arrive in the final ~25 s).
for (let i = 0; i < 120 && !logs().some((m) => /fixture real_autoflow_day finished/u.test(m)); i++) {
  const opt = targets().find(isOption);
  if (opt) { press(opt.name); await look(0, 1.5, -0.44); }
  await sleep(600);
}
// The inbox-zero payoff stays up 6.5 s: shoot it straight away.
shot('stations_inbox_zero');
rest();
await sleep(7000);
const st2 = centre(stationButtons());
press(st2.name); rest(); await sleep(1200);
await look(0, 1.5, -0.44);
shot('stations_promoted');
const promotedDump = targets();
save('targets_promoted', promotedDump);
const ergoPromoted = ergonomics(promotedDump, 'station promoted (tray + promoted card)');
const log = logs().filter((m) => m.includes('[overhand]'));
save('console', log);
save('ergonomics', {
  guided_beat: { target: station.name, angle_deg: +angle.toFixed(2), within_60: Math.abs(angle) <= 60 },
  decision: ergoDecision, promoted: ergoPromoted,
  inbox_zero_logged: log.some((m) => /inbox zero/u.test(m)),
});
console.log(JSON.stringify({ guided_angle: angle, decision: { min: ergoDecision.distance_min_m, max: ergoDecision.distance_max_m, replay: ergoDecision.replay_night_m, gap: ergoDecision.min_gap_mm }, promoted: { min: ergoPromoted.distance_min_m, max: ergoPromoted.distance_max_m, gap: ergoPromoted.min_gap_mm } }));
