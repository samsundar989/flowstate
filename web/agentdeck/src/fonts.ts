/**
 * Prebuilt MSDF type (task #110). UIKitML only accepts a custom `font-family` that an
 * `@font-face` rule declares with a `.ttf` src, and would then build the atlas at runtime in a
 * wasm worker (slow on a Quest). Every .uikitml declares its faces as
 * `url(../fonts/<mono|sans>-<regular|semibold>.ttf)`; this shim answers those URLs with the
 * atlases generated offline (public/fonts/*.json + .png, assets/fonts/README.md), so no TTF is
 * shipped and no atlas is generated on the headset. Import before World.create.
 */
import { UIKit } from '@iwsdk/core';

const FACE = /\/fonts\/((?:mono|sans)-(?:regular|semibold))\.ttf(?:[?#].*)?$/u;

type Loader = { loadAsync(input: unknown, onProgress?: unknown): Promise<unknown> };
const proto = (UIKit as unknown as { TTFLoader: { prototype: Loader } }).TTFLoader.prototype;
const original = proto.loadAsync;
proto.loadAsync = function (this: Loader, input: unknown, onProgress?: unknown) {
  const url = typeof input === 'string' ? input : undefined;
  const m = url ? FACE.exec(url) : null;
  if (!url || !m) return original.call(this, input, onProgress);
  // Shape uikitml expects from a TTF load: { family: { weight: FontInfoSource } }.
  return Promise.resolve({ [m[1]]: { normal: url.replace(FACE, `/fonts/${m[1]}.json`) } });
};

export {};
