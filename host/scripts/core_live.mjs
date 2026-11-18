// Core end-to-end, recorded: start the real host (separate process, LAN address, the given agent), run the fake headset
// (another process) from the pairing URI the host prints, collect the event log, the project's git log, the program's
// output and the sanitized raw agent streams, stop the host.
// Usage: node scripts/core_live.mjs <codex|claude|replay> <out dir> "<prompt>"
import { spawn, spawnSync } from 'node:child_process';
import { mkdirSync, writeFileSync, copyFileSync, readdirSync, readFileSync, rmSync, existsSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { tmpdir } from 'node:os';

const [agent, outArg, prompt] = process.argv.slice(2);
const out = resolve(outArg); mkdirSync(out, { recursive: true });
const work = join(tmpdir(), `fhost-core-${agent}-${Date.now()}`);
const cfg = join(work, 'cfg'), projects = join(work, 'projects');
const node = process.execPath, ts = ['--experimental-strip-types', '--no-warnings'];
const hostArgs = [...ts, 'src/main.ts', '--agent', agent, '--config', cfg, '--projects', projects, '--no-mdns'];
if (agent === 'replay') hostArgs.push('--replay', resolve('test/fixtures/streams/codex_ask.jsonl'));
const host = spawn(node, hostArgs, { env: { ...process.env, FLOWSTATE_PRINT_URI: '1' }, stdio: ['ignore', 'pipe', 'pipe'] });
let hostLog = '';
const uri = await new Promise((res, rej) => {
  const t = setTimeout(() => rej(new Error('host did not print a pairing URI')), 30000);
  const on = d => { hostLog += d; const m = hostLog.match(/PAIR_URI (\S+)/); if (m) { clearTimeout(t); res(m[1]); } };
  host.stdout.on('data', on); host.stderr.on('data', on);
});
const bind = new URL(uri).searchParams.get('h');
const fh = spawnSync(node, [...ts, 'test/core_headset.ts', '--uri', uri, '--prompt', prompt, '--out', join(out, 'headset.json')], { encoding: 'utf8', timeout: 25 * 60_000 });
writeFileSync(join(out, 'headset.log'), fh.stdout + fh.stderr);
// status from the command line while the host is still up, then stop it the way a user would
const st = spawnSync(node, [...ts, 'src/main.ts', 'status', '--config', cfg], { encoding: 'utf8' });
writeFileSync(join(out, 'cli_status.txt'), `$ flowstate-host status\n${st.stdout}${st.stderr}`);
const sp = spawnSync(node, [...ts, 'src/main.ts', 'stop', '--config', cfg], { encoding: 'utf8' });
writeFileSync(join(out, 'cli_stop.txt'), `$ flowstate-host stop\n${sp.stdout}${sp.stderr}`);
await new Promise(r => setTimeout(r, 500));
host.kill();
const redact = s => s.split(work).join('<work>').split(work.split(String.fromCharCode(92)).join('/')).join('<work>').replace(/PAIR_URI \S+/g, 'PAIR_URI [redacted]').replace(/t=[A-Za-z0-9_-]{22}/g, 't=[redacted]').replace(/C:\\Users\\[^\\\s"]+/gi, '<home>');
writeFileSync(join(out, 'host_terminal.log'), redact(hostLog));
copyFileSync(join(cfg, 'events.jsonl'), join(out, 'events.jsonl'));
const proj = readdirSync(projects)[0], dir = join(projects, proj);
for (const f of readdirSync(join(cfg, 'raw'))) {
  // project path -> {{CWD}}, home -> ~ (scripts/sanitize_stream.mjs); stderr lines are dropped, never committed raw
  const tmpOut = join(work, `san_${f}`);
  const r = spawnSync(node, ['scripts/sanitize_stream.mjs', join(cfg, 'raw', f), dir, tmpOut], { encoding: 'utf8' });
  writeFileSync(join(out, `raw_${f}`), r.status === 0 && existsSync(tmpOut) ? redact(readFileSync(tmpOut, 'utf8')) : `sanitize failed: ${r.stderr}`);
}
const gitlog = spawnSync('git', ['log', '--stat', '--format=commit %h%nDate: %cI%n%n    %s%n', 'main'], { cwd: dir, encoding: 'utf8' }).stdout;
writeFileSync(join(out, 'git_log.txt'), gitlog);
writeFileSync(join(out, 'git_worktrees.txt'), redact(spawnSync('git', ['worktree', 'list'], { cwd: dir, encoding: 'utf8' }).stdout));
const files = spawnSync('git', ['ls-files'], { cwd: dir, encoding: 'utf8' }).stdout.split('\n').filter(Boolean);
const entry = files.find(f => /\.(py|js)$/.test(f));
const py = entry?.endsWith('.py');
const prog = entry ? spawnSync(py ? (process.platform === 'win32' ? 'python' : 'python3') : node, [entry], { cwd: dir, encoding: 'utf8' }) : { stdout: '', stderr: 'no program', status: null };
writeFileSync(join(out, 'program_output.txt'), `$ ${py ? 'python' : 'node'} ${entry}\n${prog.stdout}${prog.stderr}exit=${prog.status}\n`);
mkdirSync(join(out, 'project'), { recursive: true });
for (const f of files) copyFileSync(join(dir, f), join(out, 'project', f.replace(/[\\/]/g, '_')));
writeFileSync(join(out, 'meta.json'), JSON.stringify({ agent, bind, project: proj, files, headset_exit: fh.status, prompt }, null, 1));
console.log(`bind=${bind} headset_exit=${fh.status}`); console.log(fh.stdout.split('\n').slice(-30).join('\n'));
if (!process.env.KEEP_WORK) rmSync(work, { recursive: true, force: true });
