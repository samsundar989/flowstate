// Task #474: the served hand models stay the exact licensed upstream bytes, loaded from this origin.
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';

const read = p => readFileSync(new URL(`../${p}`, import.meta.url));
const dir = 'public/hands/generic-hand';
const sha256 = b => createHash('sha256').update(b).digest('hex');

test('hand models are the exact @webxr-input-profiles/assets@1.0.20 bytes', () => {
  assert.equal(sha256(read(`${dir}/left.glb`)), 'bc67783144944ea1cda54d9247885825ea5fb9d4651469fe7d00be517a5c2b87');
  assert.equal(sha256(read(`${dir}/right.glb`)), '291790c14f7f88a7f9bd35330c47392ed8e8d395ae6728f4bb7089f1bc1f2b96');
});

test('the MIT licence and its copyright line travel with the models', () => {
  const licence = read(`${dir}/LICENSE.md`).toString();
  assert.match(licence, /^MIT License/);
  assert.match(licence, /Copyright \(c\) 2019 Amazon/);
  assert.match(read(`${dir}/NOTICE.md`).toString(), /assets@1\.0\.20/);
});

test('bootstrap installs the local hands, and names no CDN', () => {
  assert.match(read('src/index.ts').toString(), /useLocalHandModels\(world\)/);
  assert.doesNotMatch(read('src/hand-assets.ts').toString(), /https?:/);
});
