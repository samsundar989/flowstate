/**
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import { AssetType, DEFAULT_PROFILES_PATH, defineAssets } from '@iwsdk/core';
import { ghostPoke, ghostPinch, ghostThumb } from './scene-assets/ghost-hand.scene-asset.js';
const publicAssetUrl = (filePath: string): string =>
  `${import.meta.env.BASE_URL}${filePath.replace(/^\/+/u, '')}`;
const uikitml = (fileName: string, name: string) => ({
  url: publicAssetUrl(`ui/${fileName}`),
  type: AssetType.UIKitML,
  name,
});

// The SDK always asks for controller models at its public-CDN path, whatever the visual class says.
// A manifest id equal to that URL resolves the load to our own copy; 'lazy' means nothing is fetched
// until a controller connects. See public/controllers/touch/NOTICE.md.
const controller = (side: 'left' | 'right') => ({
  [`${DEFAULT_PROFILES_PATH}/meta-quest-touch-plus/${side}.glb`]: {
    url: publicAssetUrl(`controllers/touch/${side}.glb`),
    type: AssetType.GLTF,
    priority: 'lazy' as const,
  },
});

export default defineAssets({
  'ghost-poke': ghostPoke,
  'ghost-pinch': ghostPinch,
  'ghost-thumb': ghostThumb,
  'project-station': uikitml('station.uikitml', 'Project Station'),
  'decision-card': uikitml('decision.uikitml', 'Decision Card'),
  'decision-option': uikitml('option.uikitml', 'Decision Option'),
  'agent-panel': uikitml('panel.uikitml', 'Agent Panel'),
  'task-tray': uikitml('tray.uikitml', 'Task Tray'),
  'node-card': uikitml('node-card.uikitml', 'Node Card'),
  ...controller('left'),
  ...controller('right'),
});
