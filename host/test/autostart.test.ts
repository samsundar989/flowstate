import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, existsSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
const bin = fileURLToPath(new URL('../bin/flowstate-host.mjs', import.meta.url));
const run = (...args: string[]) => spawnSync(process.execPath, [bin, 'autostart', ...args], { encoding: 'utf8' });
test('autostart rejects invalid actions and missing override paths before doing anything', () => {
  for (const args of [[], ['maybe'], ['on', '--startup-dir'], ['on', '--other', 'path']]) {
    const result = run(...args);
    assert.equal(result.status, 1);
    assert.match(result.stderr, /Usage:/);
  }
});
test('Windows login shortcut points at this checkout; on/off are idempotent and leave no entry', { skip: process.platform !== 'win32' }, () => {
  const folder = mkdtempSync(join(tmpdir(), 'flowstate startup test '));
  const entry = join(folder, 'Flowstate host.lnk');
  try {
    for (let i = 0; i < 2; i++) {
      const result = run('on', '--startup-dir', folder);
      assert.equal(result.status, 0, result.stderr);
      assert.ok(existsSync(entry));
    }
    const inspect = spawnSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command',
      '$s = New-Object -ComObject WScript.Shell; $l = $s.CreateShortcut($env:FLOWSTATE_TEST_ENTRY); $l.TargetPath; $l.WorkingDirectory'],
      { encoding: 'utf8', env: { ...process.env, FLOWSTATE_TEST_ENTRY: entry } });
    assert.equal(inspect.status, 0, inspect.stderr);
    const root = fileURLToPath(new URL('../../', import.meta.url)).replace(/[\\/]$/, '');
    assert.ok(inspect.stdout.includes(join(root, 'Start Flowstate host.cmd')), inspect.stdout);
    assert.ok(inspect.stdout.includes(root), inspect.stdout);
    for (let i = 0; i < 2; i++) {
      const result = run('off', '--startup-dir', folder);
      assert.equal(result.status, 0, result.stderr);
      assert.equal(existsSync(entry), false);
    }
  } finally { rmSync(folder, { recursive: true, force: true }); }
});
