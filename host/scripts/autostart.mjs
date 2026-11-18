import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
export function autostart(args) {
  const [action, ...options] = args;
  if (!['on', 'off'].includes(action) || (options.length && (options.length !== 2 || options[0] !== '--startup-dir' || !options[1]))) {
    console.error('Usage: flowstate-host autostart on|off [--startup-dir <folder>]');
    return 1;
  }
  if (process.platform !== 'win32') {
    console.log('Start at login is Windows-only for now. Start the host with sh ./start-host.sh on Mac or Linux.');
    return 0;
  }
  const script = fileURLToPath(new URL('./autostart.ps1', import.meta.url));
  const launcher = fileURLToPath(new URL('../../Start Flowstate host.cmd', import.meta.url));
  const result = spawnSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', script,
    '-Action', action, '-Launcher', launcher, ...(options.length ? ['-StartupDir', options[1]] : [])], { stdio: 'inherit' });
  if (result.error) console.error(`Could not change start at login: ${result.error.message}`);
  return result.status ?? 1;
}
