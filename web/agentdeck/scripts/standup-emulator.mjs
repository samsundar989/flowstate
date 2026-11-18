// CPU-only managed IWSDK emulator acceptance. Run from web/agentdeck.
import { spawnSync } from 'node:child_process';
import { writeFileSync } from 'node:fs';
const out = process.env.EMU_OUT ?? '../../runs/sim/web_standup_';
const cli = (domain, action, input = {}, flags = []) => {
  const r = spawnSync(process.execPath, ['node_modules/@iwsdk/cli/bin/iwsdk.js', domain, action,
    '--input-json', JSON.stringify(input), ...flags], { encoding: 'utf8', timeout: 65000, maxBuffer: 16 * 1024 * 1024 });
  if (r.error) throw r.error;
  if (!r.stdout.trim()) throw Error(`${domain} ${action} exit=${r.status}: ${r.stderr}`);
  const parsed = JSON.parse(r.stdout);
  if (!parsed.ok) throw Error(JSON.stringify(parsed));
  return parsed.data.result ?? parsed.data;
};
const save = (name, value) => writeFileSync(out + name + '.json', JSON.stringify(value, null, 2));
const shot = name => cli('browser', 'screenshot', {}, ['--output-file', out + name + '.png']);
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const press = target => cli('xr', 'run-sequence', { device: 'hand-right', steps: [{ target, press: {} }] }, ['--timeout', '45000']);
const hand = (poseId, duration = 0.8) => cli('xr', 'run-sequence', { device: 'hand-right', steps: [
  { position: { relativeTo: 'head', x: 0.22, y: -0.08, z: -0.30 }, anchor: 'wrist', orientation: { fingers: 'forward', thumb: 'up' }, poseId }, { duration },
] }, ['--timeout', '45000']);
const stage = process.argv[2] ?? 'inspect';
if (stage === 'final-tutorial') {
  cli('xr', 'enter'); cli('xr', 'set-input-mode', { mode: 'hand' });
  cli('xr', 'set-transform', { device: 'headset', position: { x: 0, y: 1.6, z: 0 } });
  cli('xr', 'look-at', { device: 'headset', target: { x: 0, y: 1.6, z: -1 } });
  press('tutorial-button'); shot('summary');
  press('skip-beat');
  press('Station Geothermal faults/promote-button');
  const targets = cli('xr', 'list-targets');
  const p = targets.targets.find(t => t.name === 'scrub-knob').position;
  cli('xr', 'set-transform', { device: 'hand-right', position: { x: 0.18, y: 1.3, z: 0.1 } });
  cli('xr', 'look-at', { device: 'hand-right', target: p });
  cli('xr', 'set-select-value', { device: 'hand-right', value: 1 });
  cli('xr', 'look-at', { device: 'hand-right', target: { ...p, x: p.x - 0.14 } });
  shot('scrub'); cli('xr', 'set-select-value', { device: 'hand-right', value: 0 });
  hand('default', 0.2); await sleep(1100); shot('tutorial_thumbs');
  hand('thumbs-up', 1.0); shot('inbox_zero');
  save('console_tutorial_final', cli('browser', 'logs', { count: 150 }));
  press('replay-night'); await sleep(1000); shot('replay_30x');
  console.log('Final guided controls exercised');
} else
if (stage === 'inspect') {
  cli('xr', 'enter'); cli('xr', 'set-input-mode', { mode: 'hand' });
  save('targets', cli('xr', 'list-targets'));
  save('entities', cli('ecs', 'find', { namePattern: 'Decision Inbox|Task Tray|Guide hand', limit: 15 }));
  save('console_initial', cli('browser', 'logs', { count: 100 }));
  console.log('Saved stand-up state');
} else if (stage === 'tutorial') {
  press('tutorial-button'); shot('summary');
  // Summary advances automatically after 5 s; start station beat through the tray.
  press('skip-beat');
  const targets = cli('xr', 'list-targets');
  const station = targets.targets.find(t => t.name === 'Station Geothermal faults/promote-button');
  if (!station) throw Error('No station target');
  press(station.name);
  save('scrub_targets', cli('xr', 'list-targets'));
  const scrubTargets = cli('xr', 'list-targets');
  const knob = scrubTargets.targets.find(t => t.name === 'scrub-knob');
  const p = knob.position;
  cli('xr', 'set-transform', { device: 'hand-right', position: { x: 0.18, y: 1.3, z: 0.1 } });
  cli('xr', 'look-at', { device: 'hand-right', target: p });
  cli('xr', 'set-select-value', { device: 'hand-right', value: 1 });
  cli('xr', 'look-at', { device: 'hand-right', target: { ...p, x: p.x + 0.14 } });
  shot('scrub');
  cli('xr', 'set-select-value', { device: 'hand-right', value: 0 });
  hand('default', 0.2); await sleep(1100);
  shot('tutorial_thumbs'); hand('thumbs-up', 1.0);
  shot('replay_30x'); save('console_tutorial', cli('browser', 'logs', { count: 100 }));
  console.log('Saved guided stand-up and replay');
} else if (stage === 'thumbs') {
  hand('default', 0.2); await sleep(1100);
  shot('tutorial_thumbs'); hand('thumbs-up', 1.0);
  shot('replay_30x'); save('console_tutorial', cli('browser', 'logs', { count: 100 }));
} else if (stage === 'profile') {
  cli('browser', 'profile', { action: 'start', mode: 'rendering', maxDurationMs: 10000 });
  await sleep(10500);
  save('profile', cli('browser', 'profile', { action: 'stop' }));
} else if (stage === 'finish') {
  cli('xr', 'enter'); cli('xr', 'set-input-mode', { mode: 'hand' }); await sleep(600);
  const targets = cli('xr', 'list-targets');
  const knob = targets.targets.find(t => t.name === 'scrub-knob');
  const bar = targets.targets.find(t => t.name === 'scrubber');
  const p = knob.position;
  cli('xr', 'set-transform', { device: 'hand-right', position: { x: 0.18, y: 1.3, z: 0.1 } });
  cli('xr', 'look-at', { device: 'hand-right', target: p });
  cli('xr', 'set-select-value', { device: 'hand-right', value: 1 });
  cli('xr', 'look-at', { device: 'hand-right', target: { ...p, x: bar.position.x + bar.size.x / 2 - 0.002 } });
  cli('xr', 'set-select-value', { device: 'hand-right', value: 0 });
  await sleep(1000);
  save('finish_targets', cli('xr', 'list-targets'));
  save('console_seek', cli('browser', 'logs', { count: 100 }));
  console.log('Sought toward end; saved queue targets');
} else if (stage === 'answer-all') {
  for (let i = 0; i < 16; i++) {
    const targets = cli('xr', 'list-targets');
    const option = targets.targets.find(t => /^Decision \d+ option /u.test(t.name));
    if (!option) break;
    press(option.name);
  }
  await sleep(1500);
  shot('inbox_zero'); save('console_final', cli('browser', 'logs', { count: 200 }));
} else if (stage === 'start') {
  cli('xr', 'set-input-mode', { mode: 'hand' });
  console.log(JSON.stringify(press('replay-night')));
  await sleep(1500); shot('replay_30x');
  save('console_start', cli('browser', 'logs', { count: 50 }));
} else if (stage === 'reload') {
  cli('browser', 'reload'); await sleep(2000);
  cli('xr', 'enter');
  save('console_reload', cli('browser', 'logs', { count: 100 }));
  save('reload_targets', cli('xr', 'list-targets'));
} else if (stage === 'reload-poke' || stage === 'audit-repro' || stage === 'pacing-still') {
  // #113 second-session failure. reload-poke: answer by poke, reload, poke again.
  // audit-repro: the scorer's brand-audit session (runs/eval/web_standup/brandcheck.mjs): reload,
  // 4 Skip pokes, seek to the end, then per card thumbs-up and, if the card stays, thumbs-down;
  // then poke the front card. Every action is checked against the app's own log lines.
  const log = [];
  const say = line => { log.push(line); console.log(line); };
  const appSince = t0 => cli('browser', 'logs', { count: 200 }).filter(l => l.timestamp >= t0).map(l => l.message);
  const front = () => cli('xr', 'list-targets').targets.find(t => /^Decision \S+ option /u.test(t.name));
  const pokeFront = (session) => {
    const option = front();
    if (!option) { say(`${session}: no option target (inbox zero)`); return false; }
    const [, id, opt] = option.name.match(/^Decision (\S+) option (\S+)/u);
    const t0 = Date.now();
    const r = press(option.name);
    const ok = appSince(t0).some(l => l.includes(`poke decision ${id} -> ${opt}`));
    say(`${session}: poke '${option.name}' at (${option.position.x.toFixed(3)}, ${option.position.y.toFixed(3)}, ${option.position.z.toFixed(3)}) completed=${r.completed} registered=${ok}`);
    return ok;
  };
  const thumbs = pose => cli('xr', 'run-sequence', { device: 'hand-right', steps: [
    { position: { relativeTo: 'head', x: 0.22, y: -0.08, z: -0.30 }, anchor: 'wrist', orientation: { fingers: 'forward', thumb: pose === 'up' ? 'up' : 'down' }, poseId: 'thumbs-up' }, { duration: 1.6 },
    { position: { relativeTo: 'head', x: 0.22, y: -0.35, z: -0.15 }, anchor: 'wrist', poseId: 'default' }, { duration: 0.2 }] }, ['--timeout', '45000']);
  const enter = async () => {
    // After a reload the page needs a moment before it offers the XR session.
    for (let i = 0; ; i++) {
      try { cli('xr', 'enter'); break; } catch (e) { if (i > 30 || !/No session has been offered/u.test(e.message)) throw e; await sleep(1000); }
    }
    cli('xr', 'set-input-mode', { mode: 'hand' });
    face();
  };
  const face = () => {
    cli('xr', 'set-transform', { device: 'headset', position: { x: 0, y: 1.6, z: 0 } });
    cli('xr', 'look-at', { device: 'headset', target: { x: 0, y: 1.5, z: -1 } });
  };
  const seekEnd = async () => {
    const targets = cli('xr', 'list-targets').targets;
    const bar = targets.find(t => t.name === 'scrubber'), k = targets.find(t => t.name === 'scrub-knob').position;
    cli('xr', 'set-transform', { device: 'hand-right', position: { x: 0.12, y: 1.34, z: -0.06 } });
    cli('xr', 'look-at', { device: 'hand-right', target: k }); cli('xr', 'set-select-value', { device: 'hand-right', value: 1 });
    cli('xr', 'look-at', { device: 'hand-right', target: { ...k, x: bar.position.x + bar.size.x / 2 - 0.002 } });
    cli('xr', 'set-select-value', { device: 'hand-right', value: 0 });
    await sleep(1500);
  };
  const skips = () => {
    for (let i = 0; i < 4; i++) {
      const t0 = Date.now();
      let r; try { r = press('skip-beat'); } catch (e) { say(`skip ${i + 1}: press failed (${e.message.slice(0, 80)})`); break; }
      say(`skip ${i + 1}: completed=${r.completed} app: ${appSince(t0).filter(l => /overhand/u.test(l)).map(l => l.replace('[overhand] ', '')).join(' | ') || '(nothing)'}`);
    }
  };
  const n = Number(process.argv[3] ?? 3);
  if (stage === 'pacing-still') {
    // The collapsed-gap marker: replay from the start of the night until the first decision hold.
    spawnSync(process.execPath, ['node_modules/@iwsdk/cli/bin/iwsdk.js', 'browser', 'run', 'scripts/seed-storage.mjs'], { encoding: 'utf8', timeout: 60000 });
    cli('browser', 'reload'); await sleep(2500); await enter();
    const t0 = Date.now();
    await sleep(Number(process.argv[3] ?? 20) * 1000);
    const bar = cli('xr', 'list-targets').targets.find(t => t.name === 'scrubber').position;
    cli('xr', 'look-at', { device: 'headset', target: bar });
    await sleep(500); shot('scrubber_skips');
    const lines = appSince(t0).filter(l => /overhand/u.test(l));
    for (const l of lines) say(l);
    save('console_pacing_still', cli('browser', 'logs', { count: 200 }));
    writeFileSync(out + stage + '.log', log.map(l => `${l}\n`).join(''));
    process.exit(0);
  }
  spawnSync(process.execPath, ['node_modules/@iwsdk/cli/bin/iwsdk.js', 'browser', 'run', 'scripts/fresh-storage.mjs'], { encoding: 'utf8', timeout: 60000 });
  cli('browser', 'reload'); await sleep(2500); await enter();
  say('session 1 (fresh storage)');
  skips();
  await seekEnd(); face();
  let first = 0, second = 0;
  for (let i = 0; i < n; i++) { if (pokeFront('session 1')) first++; await sleep(600); }
  shot('session1');
  cli('browser', 'reload'); await sleep(2500); await enter(); await sleep(1500);
  say('session 2 (after reload)');
  if (stage === 'audit-repro') {
    skips();
    await seekEnd(); face();
    for (let i = 0; i < 20; i++) {
      const opt = front(); if (!opt) break;
      const id = opt.name.match(/^Decision (\S+) /u)[1];
      if (id === '9') { face(); await sleep(500); shot('decision9_card'); }
      let t0 = Date.now(); thumbs('up'); await sleep(800);
      const up = appSince(t0).filter(l => /gesture|poke required|decision_id=/u.test(l)).map(l => l.replace('[overhand] ', ''));
      say(`decision ${id}: thumbs-up -> ${up.join(' | ') || '(nothing)'}`);
      const still = front();
      if (still && still.name.startsWith(`Decision ${id} `)) {
        t0 = Date.now(); thumbs('down'); await sleep(800);
        const down = appSince(t0).filter(l => /gesture|snooze/u.test(l)).map(l => l.replace('[overhand] ', ''));
        say(`decision ${id}: still open; thumbs-down -> ${down.join(' | ') || '(nothing)'}`);
        if (id === '9') break;
      }
    }
  }
  if (stage === 'reload-poke') { await seekEnd(); face(); } // the scorer's second session seeks before poking
  save('reload_targets', cli('xr', 'list-targets'));
  shot('after_reload');
  for (let i = 0; i < n; i++) { if (pokeFront('session 2')) second++; await sleep(600); }
  shot('session2');
  save('console_' + stage, cli('browser', 'logs', { count: 200 }));
  say(`RESULT session1 poke registered ${first}/${n}, session2 (after reload) poke registered ${second}/${n}`);
  writeFileSync(out + stage + '.log', log.map(l => `${l}\n`).join(''));
} else if (stage === 'logs') {
  save('console_final', cli('browser', 'logs', { count: 200 }));
}
