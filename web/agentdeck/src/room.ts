/**
 * The virtual study (assets/room/README.md) as the default VR background.
 * Room frame: floor origin, user faces -Z, seated eye at (0, 1.2, 0), so a design point
 * (x, y, z) is room point (x, 1.2 + y, -z). We put the room's eye anchor on the user's eye
 * and its -Z on their forward at every recentre, so the panels, tray and graph (placed from
 * the same head pose) keep their design.md positions over the desk.
 */
import {
  AssetManager,
  Color,
  Mesh,
  MeshBasicMaterial,
  MeshLambertMaterial,
  Object3D,
  Vector3,
  type Material,
  type World,
} from '@iwsdk/core';
import { deck } from './deck.js';
import { T } from './theme.js';

/**
 * Re-lit to the void palette (task #110): the GLB ships unlit with baked vertex colours; every
 * surface becomes a Lambert material (cheap on a Quest) tinted dark so the scene's warm key,
 * cyan rim and dark hemisphere light it, and the UI floats on dark the way the dashboard does.
 * The lamp's glow stays unlit and warm. Multipliers on the baked colours, per GLB material.
 */
const TINT: Record<string, string> = {
  wall: '#6A7A8E',
  wall_back: '#566678',
  ceiling: '#3A4656',
  floor_wood: '#8A7060',
  rug: '#5A6678',
  desk_wood: '#A08470',
  metal_dark: '#A0AEBE',
  books: '#8E96A2',
  plant: '#7E9A88',
  pot: '#8E8680',
  window_frame: '#6A7A8E',
};
const GLOW = '#E8B47C';

function relight(mat: Material): Material {
  const src = mat as MeshBasicMaterial;
  if (src.name === 'glow') {
    const m = src.clone();
    m.color.set(GLOW);
    return m;
  }
  return new MeshLambertMaterial({
    name: src.name,
    map: src.map ?? null,
    vertexColors: src.vertexColors,
    color: new Color(TINT[src.name] ?? '#4A5666'),
    side: src.side,
  });
}

const EYE_Y = 1.2;
let root: Object3D | null = null;
const lastEye = new Vector3();
const lastFwd = new Vector3(0, 0, -1);
let posed = false;
let hidden = false;
let away = false;
const placeFns: ((eye: Vector3, fwd: Vector3) => void)[] = [];

/** Mixed reality (?ar=1): the real room replaces the study while a session runs. */
export function hideRoom(h: boolean): void {
  hidden = h;
  if (root) root.visible = !hidden && !away;
}

/** Another environment (src/env.ts, ?env=) replaces the study and owns the background while away. */
export function setRoomAway(a: boolean): void {
  away = a;
  if (root) root.visible = !hidden && !away;
}

/** Called on every recentre with the same pose as the room; returns the unsubscribe. */
export function onPlace(fn: (eye: Vector3, fwd: Vector3) => void): () => void {
  placeFns.push(fn);
  if (posed) fn(lastEye, lastFwd);
  return () => placeFns.splice(placeFns.indexOf(fn), 1);
}

export async function loadRoom(world: World): Promise<void> {
  if (!away) world.scene.background = new Color(T.void); // the open side behind the user
  const gltf = await AssetManager.loadGLTF(`${import.meta.env.BASE_URL}room/room.glb`, 'room');
  root = gltf.scene;
  root.name = 'room';
  root.visible = !hidden && !away;
  world.createTransformEntity(root);
  if (posed) placeRoom(lastEye, lastFwd);
  let meshes = 0;
  const done = new Map<Material, Material>();
  root.traverse((o) => {
    if (!(o instanceof Mesh)) return;
    meshes++;
    const one = (m: Material) => {
      let r = done.get(m);
      if (!r) done.set(m, (r = relight(m)));
      return r;
    };
    o.material = Array.isArray(o.material) ? o.material.map(one) : one(o.material);
  });
  deck.info(`room loaded (${meshes} meshes)`);
}

/** eye = world eye position; fwd = horizontal unit forward. */
export function placeRoom(eye: Vector3, fwd: Vector3): void {
  lastEye.copy(eye);
  lastFwd.copy(fwd);
  posed = true;
  for (const fn of placeFns) fn(eye, fwd);
  if (!root) return;
  root.position.set(eye.x, eye.y - EYE_Y, eye.z);
  // Rotate about Y so the room's -Z maps onto fwd.
  root.rotation.set(0, Math.atan2(-fwd.x, -fwd.z), 0);
  root.updateMatrixWorld(true);
}
