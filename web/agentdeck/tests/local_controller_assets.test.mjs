// Task #517: the served controller models stay the exact licensed upstream bytes, and load lazily from this origin.
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';

const read = p => readFileSync(new URL(`../${p}`, import.meta.url));
const dir = 'public/controllers/touch';
const sha256 = b => createHash('sha256').update(b).digest('hex');

test('controller models are the exact @webxr-input-profiles/assets@1.0.20 bytes', () => {
  assert.equal(sha256(read(`${dir}/left.glb`)), '41a35b80221398d37029cbfaa9e39084ccd43e0206be72a5e8460f52e7882cb4');
  assert.equal(sha256(read(`${dir}/right.glb`)), '5126d638fb1d03e05d16d8eec3b31b9588df88eaa8b42cc21b857fac3bd44916');
});

test('the MIT licence is byte-identical to the package and the notice names the version', () => {
  assert.equal(sha256(read(`${dir}/LICENSE.md`)), '0ef0c87e8ffdd0681f332dc3b39f284dee2166d1f853fabc3853884fe81a6f30');
  assert.match(read(`${dir}/NOTICE.md`).toString(), /assets@1\.0\.20/);
});

test('the manifest maps the SDK path to the local file without preloading it', () => {
  const src = read('src/assets.ts').toString();
  assert.match(src, /\$\{DEFAULT_PROFILES_PATH\}\/meta-quest-touch-plus\/\$\{side\}\.glb/);
  assert.match(src, /priority: 'lazy'/);
  assert.doesNotMatch(src, /https?:/);
});
