// Dependency-free first-run bootstrap; always resolves paths relative to this checkout.
import { existsSync } from 'node:fs';
import { spawnSync, spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
const host = fileURLToPath(new URL('../', import.meta.url));
const [major, minor] = process.versions.node.split('.').map(Number);
if (!(major > 22 || (major === 22 && minor >= 6))) {
  console.error('Flowstate needs Node.js 22.6 or newer. Download it from https://nodejs.org/ then try again.');
  process.exit(1);
}
if (!existsSync(new URL('../node_modules/.package-lock.json', import.meta.url))) {
  console.log('First start: installing Flowstate host dependencies. This needs an internet connection.');
  const install = spawnSync(process.platform === 'win32' ? 'npm.cmd' : 'npm', ['ci', '--no-audit', '--no-fund'], {
    cwd: host, stdio: 'inherit', shell: process.platform === 'win32'
  });
  if (install.error || install.status !== 0) {
    console.error('Dependencies could not be installed. Check your internet connection and Node.js installation, then start Flowstate again.');
    process.exit(1);
  }
}
const child = spawn(process.execPath, [fileURLToPath(new URL('../bin/flowstate-host.mjs', import.meta.url)), ...process.argv.slice(2)], { stdio: 'inherit' });
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => child.kill(signal));
child.on('error', error => { console.error(`Could not start Flowstate: ${error.message}`); process.exitCode = 1; });
child.on('exit', code => process.exit(code ?? 1));
