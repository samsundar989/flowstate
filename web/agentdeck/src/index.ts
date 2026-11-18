/**
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import './fonts.js'; // before any UIKitML loads: prebuilt MSDF type
import { SessionMode, World } from '@iwsdk/core';
import projectOptions from 'virtual:iwsdk-project';
import { GraphSystem } from './graph/system.js';
import { TouchForHandsSystem, useLocalHandModels } from './hand-assets.js';
import { PanelSystem } from './panels.js';
import { EnvSystem } from './env.js';
import { hideRoom } from './room.js';
import { framebufferScaleFor } from './theme.js';
import { TraySystem } from './tray.js';
import { StationSystem } from './stations.js';

// Without a headset, say what this is and how to open it. Never created where immersive VR is supported;
// a desktop browser can take seconds to answer, so show it after 800 ms and drop it if VR turns out supported.
const showNotice = () => {
  const tpl = document.getElementById('flat-notice') as HTMLTemplateElement | null;
  if (!tpl) return;
  document.body.append(tpl.content);
  tpl.remove();
};
const noticeTimer = setTimeout(showNotice, 800);
// ?ar=1: mixed reality. The session is immersive-ar (button and browser offer alike); the study is hidden in session
// and the SDK clears the background for passthrough. Without the flag nothing changes.
const AR = new URLSearchParams(location.search).get('ar') === '1';
const vrSupported = (navigator.xr?.isSessionSupported(AR ? 'immersive-ar' : 'immersive-vr') ?? Promise.resolve(false)).catch(() => false);
void vrSupported
  .then((vr) => {
    clearTimeout(noticeTimer);
    if (vr) document.getElementById('xr-notice')?.remove();
    else showNotice();
  });

World.create(
  document.getElementById('scene-container') as HTMLDivElement,
  AR && projectOptions.xr ? { ...projectOptions, xr: { ...projectOptions.xr, sessionMode: SessionMode.ImmersiveAR } } : projectOptions,
).then((world) => {
  useLocalHandModels(world);
  // Resolution (task #110): supersample on Quest 3 / 3S, native on the Quest 2 (60 fps floor;
  // lower this first if it drops). Must be set before a session starts.
  const xr = world.renderer.xr;
  const scale = framebufferScaleFor(navigator.userAgent);
  xr.setFramebufferScaleFactor(scale);
  // Fixed foveation blurs the text panels at the lens edges: off while panels are in view
  // (always, in this layout).
  const onStart = () => xr.setFoveation(0);
  xr.addEventListener('sessionstart', onStart);
  if (AR) {
    xr.addEventListener('sessionstart', () => {
      hideRoom(true);
      console.info(`[overhand] session ${world.xrDefaults?.sessionMode} blend ${xr.getSession()?.environmentBlendMode}`);
    });
    xr.addEventListener('sessionend', () => hideRoom(false));
  }
  console.info(`[overhand] framebufferScale ${scale}, foveation 0`);
  // Flat screen only (no headset session): widen the vertical fov so the first panel and the
  // tray fit any window shape. While presenting, the headset sets the view; never touch it.
  const cam = world.camera;
  const fov0 = cam.fov; // the camera as the scene sets it: every headset session starts from it, not the flat fit
  const fit = () => {
    if (xr.isPresenting) return;
    const w = window.innerWidth, h = window.innerHeight, wide = w >= h;
    const tanHalf = wide ? 1.0 : Math.max(1.2, 0.5 / (w / h));
    cam.fov = (2 * Math.atan(tanHalf) * 180) / Math.PI;
    if (wide) cam.setViewOffset(w, h, 0, 0.04 * h, w, h); // the tray sits lower than the panel rises
    else cam.clearViewOffset();
  };
  fit();
  window.addEventListener('resize', fit);
  xr.addEventListener('sessionstart', () => { cam.fov = fov0; cam.clearViewOffset(); });
  // The SDK restores the pre-session camera one frame after the session ends; fit the flat view after that.
  xr.addEventListener('sessionend', () => requestAnimationFrame(fit));
  // After the first press on the scene, the notice keeps only its first line so it never covers a panel.
  const minNotice = () => document.getElementById('xr-notice')?.classList.add('min');
  world.renderer.domElement.addEventListener('pointerdown', minNotice, { once: true });
  // Where immersive VR is supported: an in-page ENTER VR control that launches the session through the
  // SDK's own call (same options as the browser's offer). Out of the DOM while a session runs.
  void vrSupported.then((vr) => {
    const tpl = document.getElementById('enter-vr') as HTMLTemplateElement | null;
    const el = tpl?.content.firstElementChild as HTMLElement | null;
    if (!vr || !el) return;
    if (AR) el.querySelector('button')!.textContent = 'ENTER MIXED REALITY';
    el.querySelector('button')!.addEventListener('click', () => world.launchXR());
    if (!xr.isPresenting) document.body.append(el);
    xr.addEventListener('sessionstart', () => el.remove());
    xr.addEventListener('sessionend', () => document.body.append(el));
  });
  world.registerSystem(TouchForHandsSystem);
  world.registerSystem(TraySystem);
  world.registerSystem(GraphSystem);
  world.registerSystem(PanelSystem);
  world.registerSystem(StationSystem);
  // ?env=moon|room (task #735): environment pack and the switch tile by the tray. Without the parameter, nothing.
  if (new URLSearchParams(location.search).has('env')) world.registerSystem(EnvSystem);
});
