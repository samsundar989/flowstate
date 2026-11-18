/**
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import { iwsdkDev } from '@iwsdk/vite-plugin-dev';
import { readdirSync, readFileSync } from 'node:fs';
import { defineConfig, searchForWorkspaceRoot, type Plugin } from 'vite';

// Environment packs (task #735): assets/env/<name>/{env.json,*.jpg,*.png} served at env/<name>/ in dev and
// emitted into dist/env/<name>/ at build, so the Web build and the native app read one copy of each pack.
const ENV_ROOT = new URL('../../assets/env/', import.meta.url);
const ENV_FILE = /^[\w-]+\.(json|jpg|png)$/;
const envFiles = () => readdirSync(ENV_ROOT, { withFileTypes: true }).filter((d) => d.isDirectory())
  .flatMap((d) => readdirSync(new URL(`${d.name}/`, ENV_ROOT)).filter((f) => ENV_FILE.test(f)).map((f) => `${d.name}/${f}`));
function envPacks(): Plugin {
  const type: Record<string, string> = { json: 'application/json', jpg: 'image/jpeg', png: 'image/png' };
  return {
    name: 'env-packs',
    configureServer(server) {
      server.middlewares.use('/env/', (req, res, next) => {
        const rel = decodeURIComponent((req.url ?? '').split('?')[0].replace(/^\//, ''));
        if (!envFiles().includes(rel)) return next();
        res.setHeader('Content-Type', type[rel.split('.').pop()!]);
        res.end(readFileSync(new URL(rel, ENV_ROOT)));
      });
    },
    generateBundle() {
      for (const rel of envFiles()) this.emitFile({ type: 'asset', fileName: `env/${rel}`, source: readFileSync(new URL(rel, ENV_ROOT)) });
    },
  };
}

export default defineConfig(({ command }) => ({
  // Vite merges matching public variables into whole-env replacements, too.
  envPrefix: [],
  // Dependencies probe the whole env object. Publish only these runtime constants.
  define: command === 'build' ? { 'import.meta.env': JSON.stringify({
    BASE_URL: './', DEV: false, PROD: true, MODE: 'production', SSR: false,
  }) } : {},
  cacheDir: 'node_modules/.vite-t112',
  plugins: [iwsdkDev(), envPacks()],
  server: {
    host: '0.0.0.0',
    port: Number(process.env.AGENTDECK_PORT) || 8081, // override when another worktree holds 8081
    open: false,
    // src/deck.ts bundles the replay fixtures straight from agents/fixtures/.
    fs: { allow: [searchForWorkspaceRoot(process.cwd()), '../../agents/fixtures'] },
  },
  build: {
    outDir: 'dist',
    sourcemap: process.env.NODE_ENV !== 'production',
    target: 'esnext',
    rollupOptions: { input: './index.html' },
  },
  esbuild: { target: 'esnext' },
  // @drawcall/uikitml otherwise pulls a second three/@pmndrs/uikit graph
  // (its own three vs the app's super-three alias). Duplicate Component classes break
  // instanceof checks → "Only pmndrs/uikit components can be added as children".
  resolve: {
    dedupe: [
      'three',
      '@pmndrs/uikit',
      '@pmndrs/uikit-horizon',
      '@pmndrs/uikit-lucide',
    ],
  },
  optimizeDeps: {
    exclude: ['@babylonjs/havok'],
    include: [
      'three',
      '@pmndrs/uikit',
      '@pmndrs/uikit-horizon',
      '@pmndrs/uikit-lucide',
      '@drawcall/uikitml',
    ],
    esbuildOptions: { target: 'esnext' },
  },
  publicDir: 'public',
  base: './',
}));
