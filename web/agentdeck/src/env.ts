/**
 * Environments (task #735): `?env=moon|room` picks the background, and a small tile beside the tray cycles
 * room / moon by hand. Without the `env` parameter this module does nothing (the system is not registered).
 * A pack is assets/env/<name>/env.json plus its images (served at env/<name>/, see vite.config.ts); the native
 * app reads the same manifest. Frame: origin on the real floor (y 0) under the eye at the last recentre, -Z the
 * user's forward then; azimuth clockwise from forward (+90 = right), elevation above the eye's horizontal.
 * Everything here is unlit (baked or shader-lit), so the scene lights that shape the panels are untouched.
 */
import {
  BufferGeometry,
  CanvasTexture,
  Color,
  createSystem,
  EquirectangularReflectionMapping,
  Euler,
  Float32BufferAttribute,
  Group,
  Mesh,
  MeshBasicMaterial,
  Object3D,
  PlaneGeometry,
  PokeInteractable,
  RayInteractable,
  RepeatWrapping,
  ShaderMaterial,
  SphereGeometry,
  SRGBColorSpace,
  Texture,
  TextureLoader,
  Vector3,
} from '@iwsdk/core';
import { deck } from './deck.js';
import { onPlace, setRoomAway } from './room.js';
import { T } from './theme.js';

export const ENVS = ['room', 'moon'] as const;
export type EnvName = (typeof ENVS)[number];
const D2R = Math.PI / 180;
const EYE_Y = 1.2; // seated eye above the floor, as room.ts

type Curve = [number, number][];
interface Pack {
  name: string;
  label: string;
  sky: { image: string; intensity: number; yaw_deg: number };
  sun: { azimuth_deg: number; elevation_deg: number };
  ground: { height: string; radius_m: number; size_m: number; flat_radius_m: number; relief: Curve; rim_lift_m: Curve;
    albedo: [number, number, number]; detail: string; detail_tile_m: number };
  bodies: { name: string; image: string; azimuth_deg: number; elevation_deg: number; distance_m: number;
    angular_diameter_deg: number; tilt_deg: number; spin_deg_per_s: number; atmosphere: [number, number, number] }[];
}

/** Direction in the env frame (-Z forward, +X right). */
function dir(az: number, el: number, out = new Vector3()): Vector3 {
  return out.set(Math.sin(az * D2R) * Math.cos(el * D2R), Math.sin(el * D2R), -Math.cos(az * D2R) * Math.cos(el * D2R));
}
function curve(c: Curve, r: number): number {
  if (r <= c[0][0]) return c[0][1];
  for (let i = 1; i < c.length; i++) {
    if (r <= c[i][0]) { const [r0, v0] = c[i - 1], [r1, v1] = c[i]; return v0 + ((v1 - v0) * (r - r0)) / (r1 - r0); }
  }
  return c[c.length - 1][1];
}
const loadTex = (url: string) => new TextureLoader().loadAsync(url).then((t) => { t.colorSpace = SRGBColorSpace; return t; });

async function heightSampler(url: string): Promise<(u: number, v: number) => number> {
  const img = await new Promise<HTMLImageElement>((res, rej) => { const i = new Image(); i.onload = () => res(i); i.onerror = rej; i.src = url; });
  const c = document.createElement('canvas');
  c.width = img.width; c.height = img.height;
  const g = c.getContext('2d', { willReadFrequently: true })!;
  g.drawImage(img, 0, 0);
  const px = g.getImageData(0, 0, c.width, c.height).data, w = c.width, h = c.height;
  const at = (x: number, y: number) => px[4 * (Math.min(h - 1, Math.max(0, y)) * w + Math.min(w - 1, Math.max(0, x)))] / 127.5 - 1;
  return (u, v) => {
    const x = u * (w - 1), y = v * (h - 1), x0 = Math.floor(x), y0 = Math.floor(y), fx = x - x0, fy = y - y0;
    return (at(x0, y0) * (1 - fx) + at(x0 + 1, y0) * fx) * (1 - fy) + (at(x0, y0 + 1) * (1 - fx) + at(x0 + 1, y0 + 1) * fx) * fy;
  };
}

/** Polar ground: rings closer near the seat, flat inside flat_radius_m, sun-shaded into vertex colours. */
function buildGround(p: Pack, height: (u: number, v: number) => number, detail: Texture): Mesh {
  const g = p.ground, sun = dir(p.sun.azimuth_deg, p.sun.elevation_deg);
  const SEG = 128, RINGS = 56;
  const radii = [0];
  for (let i = 1; i <= RINGS; i++) radii.push(0.6 * Math.pow(g.radius_m / 0.6, (i - 1) / (RINGS - 1)));
  const hAt = (x: number, z: number) => {
    const r = Math.hypot(x, z);
    if (r <= g.flat_radius_m) return 0;
    return height(x / g.size_m + 0.5, z / g.size_m + 0.5) * curve(g.relief, r) + curve(g.rim_lift_m, r);
  };
  const pos: number[] = [], col: number[] = [], uv: number[] = [], idx: number[] = [];
  const n = new Vector3(), e = 0.5;
  for (let ri = 0; ri <= RINGS; ri++) {
    const segs = ri === 0 ? 1 : SEG;
    for (let s = 0; s < segs; s++) {
      const a = (s / SEG) * Math.PI * 2, r = radii[ri];
      const x = Math.sin(a) * r, z = -Math.cos(a) * r, y = hAt(x, z);
      pos.push(x, y, z);
      uv.push(x / g.detail_tile_m, z / g.detail_tile_m);
      n.set(hAt(x - e, z) - hAt(x + e, z), 2 * e, hAt(x, z - e) - hAt(x, z + e)).normalize();
      const lit = 0.16 + 2.1 * Math.max(0, n.dot(sun));
      col.push(g.albedo[0] * lit, g.albedo[1] * lit, g.albedo[2] * lit);
    }
  }
  const ring = (ri: number) => (ri === 0 ? 0 : 1 + (ri - 1) * SEG);
  for (let s = 0; s < SEG; s++) idx.push(0, ring(1) + ((s + 1) % SEG), ring(1) + s);
  for (let ri = 1; ri < RINGS; ri++) {
    for (let s = 0; s < SEG; s++) {
      const a = ring(ri) + s, b = ring(ri) + ((s + 1) % SEG), c = ring(ri + 1) + s, d = ring(ri + 1) + ((s + 1) % SEG);
      idx.push(a, b, c, b, d, c);
    }
  }
  const geo = new BufferGeometry();
  geo.setAttribute('position', new Float32BufferAttribute(pos, 3));
  geo.setAttribute('color', new Float32BufferAttribute(col, 3));
  geo.setAttribute('uv', new Float32BufferAttribute(uv, 2));
  geo.setIndex(idx);
  geo.computeBoundingSphere();
  detail.wrapS = detail.wrapT = RepeatWrapping;
  detail.anisotropy = 4;
  const m = new Mesh(geo, new MeshBasicMaterial({ map: detail, vertexColors: true }));
  m.name = 'env-ground';
  return m;
}

/** A globe lit by the env's sun (day side, soft terminator, thin atmosphere rim). Unlit by the scene's lights. */
function buildBody(b: Pack['bodies'][number], map: Texture, sunWorld: Vector3): { root: Group; spin: Object3D } {
  const root = new Group(), tilt = new Group();
  root.name = `env-${b.name}`;
  root.position.copy(dir(b.azimuth_deg, b.elevation_deg).multiplyScalar(b.distance_m));
  root.position.y += EYE_Y;
  root.lookAt(0, EYE_Y, 0); // +Z toward the seat; pitch the equator toward the viewer, then the axial tilt
  tilt.rotation.set(-b.elevation_deg * D2R, 0, b.tilt_deg * D2R);
  const radius = b.distance_m * Math.tan((b.angular_diameter_deg / 2) * D2R);
  const mat = new ShaderMaterial({
    uniforms: { map: { value: map }, sun: { value: sunWorld }, atmo: { value: new Color(...b.atmosphere) } },
    vertexShader: `varying vec2 vUv; varying vec3 vN; varying vec3 vV;
      void main() { vUv = uv; vec4 w = modelMatrix * vec4(position, 1.0); vN = normalize(mat3(modelMatrix) * normal);
        vV = normalize(cameraPosition - w.xyz); gl_Position = projectionMatrix * viewMatrix * w; }`,
    fragmentShader: `uniform sampler2D map; uniform vec3 sun; uniform vec3 atmo; varying vec2 vUv; varying vec3 vN; varying vec3 vV;
      void main() { vec3 n = normalize(vN); float l = dot(n, sun); float day = smoothstep(-0.06, 0.22, l);
        float rim = pow(1.0 - max(dot(n, normalize(vV)), 0.0), 3.0);
        vec3 c = texture2D(map, vUv).rgb * (0.015 + 1.05 * day) + atmo * rim * 0.55 * smoothstep(-0.25, 0.35, l);
        gl_FragColor = vec4(c, 1.0);
        #include <colorspace_fragment>
      }`,
  });
  const spin = new Mesh(new SphereGeometry(radius, 64, 32), mat);
  tilt.add(spin);
  root.add(tilt);
  return { root, spin };
}

/** The tile: a lit-panel-styled label drawn on a canvas, "SCENE" over the current environment's name. */
function drawTile(c: HTMLCanvasElement, name: string): void {
  const g = c.getContext('2d')!;
  const w = c.width, h = c.height;
  g.fillStyle = T.deck;
  g.fillRect(0, 0, w, h);
  g.strokeStyle = T.seamHi;
  g.lineWidth = 6;
  g.strokeRect(3, 3, w - 6, h - 6);
  g.textAlign = 'center';
  g.fillStyle = T.ink2;
  g.font = '600 34px sans-serif';
  g.fillText('SCENE', w / 2, h * 0.36);
  g.fillStyle = T.ink;
  g.font = '600 64px sans-serif';
  g.fillText(name.toUpperCase(), w / 2, h * 0.8);
}

const TILE_W = 0.07, TILE_H = 0.04, TRAY_HALF_W = 0.15, TILE_GAP = 0.012;

export class EnvSystem extends createSystem({}) {
  private current: EnvName = 'room';
  private moon: { root: Group; spins: { obj: Object3D; rate: number }[]; sky: Texture; yaw: number; sunLocal: Vector3; sunWorld: Vector3 } | null = null;
  private loading: Promise<void> | null = null;
  private placed = { eye: new Vector3(), fwd: new Vector3(0, 0, -1), yaw: 0, ok: false };
  private arHidden = false;
  private tile!: Mesh;
  private canvas = document.createElement('canvas');
  private right = new Vector3();

  init(): void {
    const want = new URLSearchParams(location.search).get('env');
    this.canvas.width = 280; this.canvas.height = 160;
    const tex = new CanvasTexture(this.canvas);
    tex.colorSpace = SRGBColorSpace;
    this.tile = new Mesh(new PlaneGeometry(TILE_W, TILE_H), new MeshBasicMaterial({ map: tex }));
    this.tile.name = 'Env tile';
    this.tile.visible = false;
    const entity = this.world.createTransformEntity(this.tile);
    entity.addComponent(RayInteractable);
    entity.addComponent(PokeInteractable);
    let armed = false;
    const obj = this.tile as unknown as { addEventListener(t: string, f: () => void): void; removeEventListener(t: string, f: () => void): void };
    const down = () => (armed = true), leave = () => (armed = false);
    const up = () => { if (armed) { armed = false; void this.set(ENVS[(ENVS.indexOf(this.current) + 1) % ENVS.length]); } };
    obj.addEventListener('pointerdown', down);
    obj.addEventListener('pointerup', up);
    obj.addEventListener('pointerleave', leave);
    this.cleanupFuncs.push(() => { obj.removeEventListener('pointerdown', down); obj.removeEventListener('pointerup', up); obj.removeEventListener('pointerleave', leave); });
    this.cleanupFuncs.push(onPlace((eye, fwd) => this.place(eye, fwd)));
    // Mixed reality (?ar=1): passthrough replaces any environment while the session runs.
    const xr = this.world.renderer.xr;
    const onStart = () => { this.arHidden = (xr.getSession()?.environmentBlendMode ?? 'opaque') !== 'opaque'; this.apply(); };
    const onEnd = () => { this.arHidden = false; this.apply(); };
    xr.addEventListener('sessionstart', onStart);
    xr.addEventListener('sessionend', onEnd);
    this.cleanupFuncs.push(() => { xr.removeEventListener('sessionstart', onStart); xr.removeEventListener('sessionend', onEnd); });
    void this.set(ENVS.includes(want as EnvName) ? (want as EnvName) : 'room');
  }

  async set(name: EnvName): Promise<void> {
    this.current = name;
    drawTile(this.canvas, name);
    ((this.tile.material as MeshBasicMaterial).map as Texture).needsUpdate = true;
    if (name === 'moon' && !this.moon) {
      this.loading ??= this.loadMoon().catch((err) => console.warn(`[overhand] env moon failed to load: ${String(err)}`));
      await this.loading;
    }
    this.apply();
    deck.info(`env ${this.current}`);
  }

  private async loadMoon(): Promise<void> {
    const base = `${import.meta.env.BASE_URL}env/moon/`;
    const p = (await (await fetch(`${base}env.json`)).json()) as Pack;
    const [sky, detail, height, ...maps] = await Promise.all([
      loadTex(base + p.sky.image), loadTex(base + p.ground.detail), heightSampler(base + p.ground.height),
      ...p.bodies.map((b) => loadTex(base + b.image)),
    ]);
    sky.mapping = EquirectangularReflectionMapping;
    const root = new Group();
    root.name = 'env-moon';
    root.add(buildGround(p, height, detail));
    const sunLocal = dir(p.sun.azimuth_deg, p.sun.elevation_deg), sunWorld = sunLocal.clone();
    const spins = p.bodies.map((b, i) => {
      const body = buildBody(b, maps[i], sunWorld);
      root.add(body.root);
      return { obj: body.spin, rate: b.spin_deg_per_s * D2R };
    });
    this.world.createTransformEntity(root);
    this.moon = { root, spins, sky, yaw: p.sky.yaw_deg * D2R, sunLocal, sunWorld };
    if (this.placed.ok) this.place(this.placed.eye, this.placed.fwd);
  }

  private apply(): void {
    const moon = this.current === 'moon' && !!this.moon;
    setRoomAway(moon);
    if (this.moon) this.moon.root.visible = moon && !this.arHidden;
    const scene = this.world.scene;
    if (moon) {
      scene.background = this.moon!.sky;
      scene.backgroundRotation = new Euler(0, this.placed.yaw + this.moon!.yaw, 0);
    } else if (scene.background instanceof Texture) {
      scene.background = new Color(T.void);
    }
  }

  private place(eye: Vector3, fwd: Vector3): void {
    this.placed.eye.copy(eye);
    this.placed.fwd.copy(fwd);
    this.placed.yaw = Math.atan2(-fwd.x, -fwd.z);
    this.placed.ok = true;
    if (!this.moon) return;
    const r = this.moon.root;
    r.position.set(eye.x, 0, eye.z); // the real floor (local-floor space)
    r.rotation.set(0, this.placed.yaw, 0);
    r.updateMatrixWorld(true);
    this.moon.sunWorld.copy(this.moon.sunLocal).applyAxisAngle(new Vector3(0, 1, 0), this.placed.yaw);
    if (this.current === 'moon') this.apply();
  }

  update(delta: number): void {
    if (this.moon?.root.visible) for (const s of this.moon.spins) s.obj.rotation.y += s.rate * delta;
    // The tile rides beside the tray's right edge, facing the same way; hidden whenever the tray is.
    const tray = this.world.getSceneEntity('task-tray')?.object3D;
    let shown = !!tray && tray.visible && tray.position.y > -10;
    for (let o = tray?.parent; shown && o; o = o.parent) shown = o.visible;
    this.tile.visible = shown;
    if (!shown || !tray) return;
    this.right.set(1, 0, 0).applyQuaternion(tray.quaternion);
    this.tile.position.copy(tray.position).addScaledVector(this.right, TRAY_HALF_W + TILE_GAP + TILE_W / 2);
    this.tile.quaternion.copy(tray.quaternion);
  }
}
