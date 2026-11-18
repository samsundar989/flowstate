#!/usr/bin/env node
// One-command start: runs src/main.ts with Node's built-in TypeScript stripping (Node >= 22).
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
const main = fileURLToPath(new URL('../src/main.ts', import.meta.url));
const c = spawn(process.execPath, ['--experimental-strip-types', '--no-warnings', main, ...process.argv.slice(2)], { stdio: 'inherit' });
for (const s of ['SIGINT', 'SIGTERM']) process.on(s, () => c.kill(s));
c.on('exit', code => process.exit(code ?? 0));
