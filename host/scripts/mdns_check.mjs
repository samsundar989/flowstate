// Starts the host (replay agent, temp config) and browses for _flowstate._tcp from a separate process-level browser,
// as the headset's NsdManager would. Prints what was discovered and whether its TXT fp matches the host.
import { spawn } from 'node:child_process';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { Bonjour } from 'bonjour-service';
const work = join(tmpdir(), `fhost-mdns-${Date.now()}`);
const host = spawn(process.execPath, ['--experimental-strip-types', '--no-warnings', 'src/main.ts', '--agent', 'replay', '--config', join(work, 'cfg'), '--projects', join(work, 'p')], { stdio: ['ignore', 'pipe', 'pipe'] });
let log = '';
const fp = await new Promise(res => host.stdout.on('data', d => { log += d; const m = log.match(/fingerprint: ([0-9a-f]{64})/); if (m && /Discoverable/.test(log)) res(m[1]); }));
const b = new Bonjour();
const found = [];
const br = b.find({ type: 'flowstate' }, s => found.push({ name: s.name, addresses: s.addresses, port: s.port, txt: s.txt }));
await new Promise(r => setTimeout(r, 4000));
br.stop(); b.destroy(); host.kill();
const mine = found.find(s => s.txt?.fp === fp);
console.log(JSON.stringify({ discovered: found.length, match: !!mine, service: mine && { ...mine, txt: { ...mine.txt, fp: mine.txt.fp.slice(0, 12) + '…' } } }, null, 1));
process.exit(mine ? 0 : 1);
