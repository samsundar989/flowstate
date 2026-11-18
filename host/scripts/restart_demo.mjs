// Restart evidence from the command line a stranger uses: start the host, `new`, `stop` it mid-task (the host and its
// agent are killed), `status`, start it again, and watch the task resume and land on main. Replayed agent (no live run).
// Usage: node scripts/restart_demo.mjs <out dir>
import { spawn, spawnSync } from 'node:child_process';
import { mkdirSync, writeFileSync, readFileSync, existsSync, readdirSync, rmSync, copyFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { tmpdir } from 'node:os';

const out = resolve(process.argv[2]); mkdirSync(out, { recursive: true });
const work = join(tmpdir(), `fhost-restart-${Date.now()}`), cfg = join(work, 'cfg'), projects = join(work, 'projects');
const node = process.execPath, ts = ['--experimental-strip-types', '--no-warnings'];
const env = { ...process.env, FAKE_AGENT_DELAY_MS: '700' };
const transcript = [];
const sh = (...args) => { const r = spawnSync(node, [...ts, 'src/main.ts', ...args, '--config', cfg], { encoding: 'utf8', env }); transcript.push(`$ flowstate-host ${args.map(a => /\s/.test(a) ? `"${a}"` : a).join(' ')}\n${r.stdout}${r.stderr}`); return r; };
const sleep = ms => new Promise(r => setTimeout(r, ms));
const startHost = async () => {
  try { rmSync(join(cfg, 'host.json')); } catch {}
  const h = spawn(node, [...ts, 'src/main.ts', '--port', '47831', '--no-mdns', '--agent', 'replay', '--replay', resolve('test/fixtures/streams/codex_build.jsonl'), '--config', cfg, '--projects', projects], { env, stdio: 'ignore' });
  for (let i = 0; i < 200 && !existsSync(join(cfg, 'host.json')); i++) await sleep(50);
  transcript.push(`$ flowstate-host --port 47831 ...   (pid ${h.pid})`);
  return h;
};
const events = () => readFileSync(join(cfg, 'events.jsonl'), 'utf8').trim().split('\n').map(l => JSON.parse(l));
await startHost();
sh('new', 'clock', '--prompt', 'a small CLI that prints the time');
for (let i = 0; i < 200 && !events().some(e => e.kind === 'file_written'); i++) await sleep(50);
const meta = JSON.parse(readFileSync(join(cfg, 'tasks', 't1.json'), 'utf8'));
sh('stop');
sh('status');
const alive = pid => { try { process.kill(pid, 0); return true; } catch { return false; } };
const agentDiedWithHost = !alive(meta.pid);
await startHost();
let done = false;
for (let i = 0; i < 400 && !done; i++) { await sleep(100); done = events().some(e => e.kind === 'task_state' && e.task_id === 't1' && ['done', 'failed'].includes(e.state)); }
sh('status');
sh('stop');
const pdir = join(projects, readdirSync(projects)[0]);
const log = spawnSync('git', ['log', '--format=%h %cI %s', 'main'], { cwd: pdir, encoding: 'utf8' }).stdout;
const ev = events().filter(e => e.task_id === 't1').map(e => `#${e.seq} ${e.kind}${e.state ? ' ' + e.state : ''}${e.reason ? ' (' + e.reason + ')' : ''}: ${e.title}`);
const recovered = events().some(e => e.task_id === 't1' && e.reason === 'host_restarted') && events().some(e => e.task_id === 't1' && e.state === 'done') && log.trim().split('\n').length === 2;
writeFileSync(join(out, 'transcript.txt'), transcript.join('\n').split(work).join('<work>'));
writeFileSync(join(out, 'task_events.txt'), ev.join('\n') + '\n');
writeFileSync(join(out, 'git_log.txt'), log);
copyFileSync(join(cfg, 'events.jsonl'), join(out, 'events.jsonl'));
writeFileSync(join(out, 'result.json'), JSON.stringify({ host_restart_recovers: recovered ? 1 : 0, agent_died_with_host: agentDiedWithHost }, null, 1));
console.log(transcript.join('\n')); console.log(ev.join('\n')); console.log(log, { recovered });
rmSync(work, { recursive: true, force: true });
