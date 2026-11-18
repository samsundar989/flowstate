// One recorded live run: start the real host (npm start) bound to the LAN address with a given agent, read the
// pairing URI it prints, run the fake headset against it from a separate process, collect evidence, stop the host.
// Usage: node scripts/live_run.mjs <claude|codex|replay> <out dir> [work dir]
import { spawn, spawnSync } from 'node:child_process';
import { mkdirSync, writeFileSync, copyFileSync, readdirSync, rmSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { tmpdir } from 'node:os';

const [agent, outArg, workArg] = process.argv.slice(2);
const out = resolve(outArg); mkdirSync(out, { recursive: true });
const work = resolve(workArg ?? join(tmpdir(), `fhost-live-${agent}-${Date.now()}`));
rmSync(work, { recursive: true, force: true });
const cfg = join(work, 'cfg'), projects = join(work, 'projects');
const node = process.execPath, ts = ['--experimental-strip-types', '--no-warnings'];
const hostArgs = [...ts, 'src/main.ts', '--agent', agent, '--config', cfg, '--projects', projects];
if (agent === 'replay') hostArgs.push('--replay', resolve('test/fixtures/streams/claude_hello.jsonl'));
const host = spawn(node, hostArgs, { env: { ...process.env, FLOWSTATE_PRINT_URI: '1' }, stdio: ['ignore', 'pipe', 'pipe'] });
let hostLog = '';
const uri = await new Promise((res, rej) => {
  const t = setTimeout(() => rej(new Error('host did not print a pairing URI')), 30000);
  const on = d => { hostLog += d; const m = hostLog.match(/PAIR_URI (\S+)/); if (m) { clearTimeout(t); res(m[1]); } };
  host.stdout.on('data', on); host.stderr.on('data', on);
});
const bind = new URL(uri).searchParams.get('h');
const fh = spawnSync(node, [...ts, 'test/fake_headset.ts', '--uri', uri, '--projects', projects, '--out', join(out, 'fake_headset.json')], { encoding: 'utf8', timeout: 600000 });
writeFileSync(join(out, 'fake_headset.log'), fh.stdout + fh.stderr);
await new Promise(r => setTimeout(r, 500));
host.kill();
await new Promise(r => setTimeout(r, 500));
// never commit the pairing token, device secret or host key: redact the URI and keep only the event log
writeFileSync(join(out, 'host_terminal.log'), hostLog.replace(/PAIR_URI \S+/g, 'PAIR_URI [redacted]').replace(/t=[A-Za-z0-9_-]{22}/g, 't=[redacted]'));
copyFileSync(join(cfg, 'events.jsonl'), join(out, 'events.jsonl'));
for (const f of readdirSync(join(cfg, 'raw'))) copyFileSync(join(cfg, 'raw', f), join(out, `raw_${f}`));
const proj = readdirSync(projects)[0];
const dir = join(projects, proj);
const gitlog = spawnSync('git', ['log', '--stat', '--format=commit %h%nAuthor: %an%nDate: %ad%n%n    %s%n'], { cwd: dir, encoding: 'utf8' }).stdout;
writeFileSync(join(out, 'git_log.txt'), gitlog);
const files = spawnSync('git', ['ls-files'], { cwd: dir, encoding: 'utf8' }).stdout.split('\n').filter(Boolean);
const entry = files.find(f => /^(hello|index|main|cli)\.(js|py)$/.test(f)) ?? files.find(f => /\.(js|py)$/.test(f));
const py = entry?.endsWith('.py');
const prog = entry ? spawnSync(py ? (process.platform === 'win32' ? 'python' : 'python3') : node, [entry], { cwd: dir, encoding: 'utf8' }) : { stdout: '', stderr: 'no program entry' };
writeFileSync(join(out, 'program_output.txt'), `$ ${py ? 'python' : 'node'} ${entry}\n${prog.stdout}${prog.stderr}exit=${prog.status}\n`);
for (const f of files) { mkdirSync(join(out, 'project'), { recursive: true }); copyFileSync(join(dir, f), join(out, 'project', f.replace(/[\\/]/g, '_'))); }
writeFileSync(join(out, 'meta.json'), JSON.stringify({ agent, bind, project: proj, files, fake_headset_exit: fh.status }, null, 1));
console.log(`bind=${bind} exit=${fh.status}`); console.log(fh.stdout.split('\n').slice(-40).join('\n'));
