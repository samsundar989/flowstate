/**
 * 3D execution graph (docs/design.md s5) in per-agent lanes between the panels, 1.0-1.4 m out.
 *
 * Draw budget: one InstancedMesh per node shape (sphere, ring, diamond, cube, hexagon root,
 * "+N" disc) sharing ONE material with per-instance colour (which also drives a status glow);
 * every edge, dash, glyph (check / cross) and lane guide in ONE LineSegments2 (2.5 mm wide in
 * world units, so it stays crisp in both eyes); one selection ring. Blueprint styling from the
 * autoflow dashboard (src/theme.ts): ion edges, dashed dependencies, mono labels. <= 8 draw calls for any node
 * count. Picking uses an invisible instanced collider (material.visible = false: no draw call;
 * pointer-events raycasts it, 8 cm targets per design s2).
 *
 * Motion: 200 ms scale-in on step_start (the parent edge grows with it); on step_end the
 * parent edge flashes (white for COMPLETED, vermilion for FAILED) and the node pops
 * (1 -> 1.15 -> 1) or shakes 4 mm. Non-colour cues: FAILED = cube + cross glyph + shake;
 * INPUT_REQUIRED = diamond + 0.5 Hz scale pulse; COMPLETED = check glyph; root = hexagon.
 */
import {
  BoxGeometry,
  BufferGeometry,
  Color,
  CylinderGeometry,
  DynamicDrawUsage,
  Group,
  InstancedMesh,
  Matrix4,
  Mesh,
  MeshBasicMaterial,
  MeshStandardMaterial,
  OctahedronGeometry,
  Quaternion,
  SphereGeometry,
  TorusGeometry,
  Vector3,
} from '@iwsdk/core';
import { LineMaterial } from 'three/examples/jsm/lines/LineMaterial.js';
import { LineSegments2 } from 'three/examples/jsm/lines/LineSegments2.js';
import { LineSegmentsGeometry } from 'three/examples/jsm/lines/LineSegmentsGeometry.js';
import type { AgentEvent, Status } from '../replay.js';
import { hex, mix, STATUS, T } from '../theme.js';
import { DEFAULT_LAYOUT, layoutGraph, type LayoutResult, type PlacedNode, type LayoutOptions } from './layout.js';

export const STATUS_COLOUR = Object.fromEntries(
  Object.entries(STATUS).map(([k, v]) => [k, hex(v.c)]),
) as Record<Status, number>;
const EDGE_PARENT = new Color(mix(T.ion, 0.55, T.void));
const EDGE_DEP = new Color(mix(T.ion, 0.4, T.void));
const EDGE_RETRY = new Color(T.red);
const GUIDE = new Color(T.seamHi);
const WHITE = new Color(T.ink);
const GLYPH = new Color(T.void);
/** Edge width in metres (LineMaterial worldUnits): ~2-3 px at 1.0-1.4 m on a Quest 3. */
const LINE_W = 0.0025;
/** Share of each node's status colour added as emission (the blueprint glow). */
const GLOW = 0.35;

const MAX_NODES = 256;
const MAX_SEGS = 8192;
const SCALE_IN_MS = 200;
const FLASH_MS = 600;
const POP_MS = 250;
const SHAKE_MS = 300;
const NODE_R = 0.03; // 6 cm visual
const ROOT_R = 0.04; // 8 cm hexagon
const COLLIDER_R = 0.04; // 8 cm collider

export interface NodeRec {
  id: string;
  lane: string;
  parent: string | null;
  deps: string[];
  retryOf?: string;
  title: string;
  tool: string;
  toolCount: number;
  detail: string;
  status: Status;
  tStartMs: number;
  tEndMs: number | null;
  bornAt: number;
  endedAt: number;
}

type Shape = 'sphere' | 'ring' | 'diamond' | 'cube' | 'hex' | 'disc';
const SHAPES: Shape[] = ['sphere', 'ring', 'diamond', 'cube', 'hex', 'disc'];

function shapeFor(rec: NodeRec | undefined, n: PlacedNode): Shape {
  if (n.members) return 'disc';
  if (rec && !rec.parent) return 'hex';
  switch (rec?.status) {
    case 'INPUT_REQUIRED':
      return 'diamond';
    case 'FAILED':
      return 'cube';
    case 'SUBMITTED':
    case 'CANCELED':
      return 'ring';
    default:
      return 'sphere';
  }
}

function makeGeometry(s: Shape): BufferGeometry {
  switch (s) {
    case 'sphere':
      return new SphereGeometry(NODE_R, 16, 12);
    case 'ring':
      return new TorusGeometry(NODE_R * 0.8, NODE_R * 0.2, 8, 24);
    case 'diamond':
      return new OctahedronGeometry(NODE_R * 1.15);
    case 'cube':
      return new BoxGeometry(NODE_R * 1.5, NODE_R * 1.5, NODE_R * 1.5);
    case 'hex':
      return new CylinderGeometry(ROOT_R, ROOT_R, 0.02, 6).rotateX(Math.PI / 2);
    case 'disc':
      return new CylinderGeometry(NODE_R * 1.1, NODE_R * 1.1, NODE_R * 0.9, 20);
  }
}

/** Worst member status decides a "+N" node's colour. */
const RANK: Status[] = ['FAILED', 'INPUT_REQUIRED', 'WORKING', 'SUBMITTED', 'COMPLETED', 'CANCELED'];

export class GraphView {
  options: LayoutOptions = DEFAULT_LAYOUT;
  readonly root = new Group();
  readonly collider: InstancedMesh;
  readonly nodes = new Map<string, NodeRec>();
  readonly expanded = new Set<string>();
  layout: LayoutResult | null = null;
  /** drawn node id -> current animated local position */
  private pos = new Map<string, Vector3>();
  private meshes = new Map<Shape, InstancedMesh>();
  private lines: LineSegments2;
  private linePos: Float32Array;
  private lineCol: Float32Array;
  private ring: Mesh;
  /** collider instance index -> drawn node id */
  drawnIds: string[] = [];
  selected: string | null = null;
  /** Drawn node under a ray (grows 15%: hover cue that does not rely on colour). */
  hovered: string | null = null;
  private dirty = false;
  private m = new Matrix4();
  private q = new Quaternion();
  private s = new Vector3();
  private v = new Vector3();
  private c = new Color();
  private segs = 0;
  /** Logged to the deck so the emulator script can see what happened. */
  log: (msg: string) => void = () => {};

  constructor() {
    this.root.name = 'Execution Graph';
    const mat = new MeshStandardMaterial({ roughness: 0.35, metalness: 0.1 });
    // Per-instance status glow: add GLOW x the instance colour as emission.
    mat.onBeforeCompile = (sh) => {
      sh.fragmentShader = sh.fragmentShader.replace(
        '#include <emissivemap_fragment>',
        `#include <emissivemap_fragment>
#ifdef USE_COLOR
 totalEmissiveRadiance += vColor.rgb * ${GLOW.toFixed(2)};
#endif`,
      );
    };
    for (const s of SHAPES) {
      const mesh = new InstancedMesh(makeGeometry(s), mat, MAX_NODES);
      mesh.name = `graph-${s}`;
      mesh.instanceMatrix.setUsage(DynamicDrawUsage);
      mesh.setColorAt(0, WHITE); // allocates instanceColor
      mesh.count = 0;
      mesh.frustumCulled = false;
      (mesh as unknown as { pointerEvents: string }).pointerEvents = 'none';
      this.meshes.set(s, mesh);
      this.root.add(mesh);
    }
    const colMat = new MeshBasicMaterial();
    colMat.visible = false; // raycast only, never drawn
    this.collider = new InstancedMesh(new SphereGeometry(COLLIDER_R, 8, 6), colMat, MAX_NODES);
    this.collider.name = 'graph-collider';
    this.collider.count = 0;
    this.collider.frustumCulled = false;
    this.root.add(this.collider);

    // Fixed-size buffers, written in place each frame (no per-frame allocation).
    const g = new LineSegmentsGeometry();
    this.linePos = new Float32Array(MAX_SEGS * 6);
    this.lineCol = new Float32Array(MAX_SEGS * 6);
    g.setPositions(this.linePos);
    g.setColors(this.lineCol);
    for (const a of ['instanceStart', 'instanceColorStart']) {
      (g.attributes[a] as unknown as { data: { setUsage(u: number): void } }).data.setUsage(
        DynamicDrawUsage,
      );
    }
    this.lines = new LineSegments2(
      g,
      new LineMaterial({ vertexColors: true, worldUnits: true, linewidth: LINE_W }),
    );
    this.lines.name = 'graph-edges';
    this.lines.frustumCulled = false;
    (this.lines as unknown as { pointerEvents: string }).pointerEvents = 'none';
    this.root.add(this.lines);

    this.ring = new Mesh(
      new TorusGeometry(0.048, 0.004, 6, 32),
      new MeshBasicMaterial({ color: T.ion }),
    );
    this.ring.name = 'graph-selection';
    this.ring.visible = false;
    (this.ring as unknown as { pointerEvents: string }).pointerEvents = 'none';
    this.root.add(this.ring);
  }

  reset(): void {
    this.nodes.clear();
    this.expanded.clear();
    this.pos.clear();
    this.selected = null;
    this.layout = null;
    this.dirty = true;
  }

  /** Feed one runtime event. */
  onEvent(e: AgentEvent, now: number): void {
    const rec = e.step_id ? this.nodes.get(e.step_id) : undefined;
    switch (e.kind) {
      case 'step_start':
        if (!e.step_id || this.nodes.has(e.step_id)) return;
        this.nodes.set(e.step_id, {
          id: e.step_id,
          lane: e.agent_id,
          parent: e.parent_step_id ?? null,
          deps: e.depends_on ?? [],
          retryOf: (e as { retry_of?: string }).retry_of,
          title: e.title ?? e.step_id,
          tool: '',
          toolCount: 0,
          detail: e.detail ?? '',
          status: 'WORKING',
          tStartMs: e.t_ms,
          tEndMs: null,
          bornAt: now,
          endedAt: 0,
        });
        this.dirty = true;
        return;
      case 'tool_call':
        if (!rec) return;
        rec.tool = e.tool ?? e.title ?? '';
        rec.toolCount++;
        if (e.detail) rec.detail = `${rec.tool}: ${e.detail}`;
        return;
      case 'step_end':
        if (!rec) return;
        rec.status = e.status ?? 'COMPLETED';
        rec.tEndMs = e.t_ms;
        rec.endedAt = now;
        if (e.detail) rec.detail = e.detail;
        return;
      case 'status': {
        const target = rec ?? this.latestWorking(e.agent_id);
        if (!target) return;
        if (e.status === 'INPUT_REQUIRED') {
          target.status = 'INPUT_REQUIRED';
          target.detail = `Asks: ${e.title ?? ''}`;
        } else if (e.status === 'WORKING' && target.status === 'INPUT_REQUIRED') {
          target.status = 'WORKING';
        }
        return;
      }
      case 'input_response': {
        const target = rec ?? this.latestWorking(e.agent_id);
        if (target?.status === 'INPUT_REQUIRED') {
          target.status = 'WORKING';
          target.detail = e.title ?? target.detail;
        }
        return;
      }
      case 'error':
        if (rec) rec.detail = `Error: ${e.title ?? ''} ${e.detail ?? ''}`.trim();
        return;
      default:
    }
  }

  private latestWorking(agent: string): NodeRec | undefined {
    let found: NodeRec | undefined;
    for (const n of this.nodes.values()) {
      if (n.lane === agent && (n.status === 'WORKING' || n.status === 'INPUT_REQUIRED')) found = n;
    }
    return found;
  }

  /** Called with a collider instance id (from a pointer event). */
  pick(instanceId: number | undefined): string | null {
    if (instanceId == null) return null;
    const id = this.drawnIds[instanceId];
    if (!id) return null;
    return this.selectDrawn(id);
  }

  hover(instanceId: number | undefined): void {
    const id = instanceId == null ? null : (this.drawnIds[instanceId] ?? null);
    if (id === this.hovered) return;
    this.hovered = id;
    this.log(`graph hover ${id ?? '-'}`);
  }

  /** Select a drawn node: a "+N" node expands, an ordinary node toggles selection. */
  selectDrawn(id: string): string | null {
    const n = this.layout?.nodes.find((x) => x.id === id);
    if (!n) return null;
    if (n.members && n.groupOf) {
      this.expanded.add(n.groupOf);
      this.dirty = true;
      this.log(`graph expand ${n.groupOf} (${n.members.length} nodes)`);
      return null;
    }
    this.selected = this.selected === id ? null : id;
    this.log(this.selected ? `graph select ${id}` : `graph deselect ${id}`);
    return this.selected;
  }

  /** Local position of a drawn node (design coordinates mapped to three: z -> -z). */
  localPos(id: string): Vector3 | undefined {
    return this.pos.get(id);
  }

  update(now: number): void {
    if (this.dirty) {
      this.dirty = false;
      const input = [...this.nodes.values()];
      this.layout = layoutGraph(input, { ...this.options, expanded: this.expanded });
      if (this.selected && !this.layout.nodes.some((n) => n.id === this.selected)) {
        this.selected = this.layout.drawnAs.get(this.selected) ?? null;
      }
    }
    const L = this.layout;
    for (const mesh of this.meshes.values()) mesh.count = 0;
    this.collider.count = 0;
    this.drawnIds.length = 0;
    this.segs = 0;
    this.drawGuides(L);
    if (!L) return this.finish();

    // Nodes.
    for (const n of L.nodes) {
      let p = this.pos.get(n.id);
      if (!p) {
        p = new Vector3(n.x, n.y, -n.z);
        this.pos.set(n.id, p);
      }
      p.x += (n.x - p.x) * 0.2;
      p.y += (n.y - p.y) * 0.2;
      p.z += (-n.z - p.z) * 0.2;
      const shown = n.layer >= L.firstLayer;
      const rec = this.nodes.get(n.id);
      const status = rec?.status ?? aggStatus(this, n);
      let k = 1;
      let dx = 0;
      if (rec) {
        const age = now - rec.bornAt;
        if (age < SCALE_IN_MS) k = easeOutBack(age / SCALE_IN_MS);
        const sinceEnd = now - rec.endedAt;
        if (rec.endedAt && sinceEnd < POP_MS && status === 'COMPLETED') {
          k *= 1 + 0.15 * Math.sin((Math.PI * sinceEnd) / POP_MS);
        }
        if (rec.endedAt && sinceEnd < SHAKE_MS && status === 'FAILED') {
          dx = 0.004 * Math.sin(sinceEnd / 15) * (1 - sinceEnd / SHAKE_MS);
        }
      }
      if (n.id === this.hovered) k *= 1.15;
      if (status === 'INPUT_REQUIRED') k *= 1 + 0.12 * Math.sin(now * 0.001 * Math.PI); // 0.5 Hz
      if (status === 'WORKING' && rec?.parent) k *= 1 + 0.04 * Math.sin(now * 0.002 * Math.PI); // 1 Hz breathe
      if (!shown) continue;

      const shape = shapeFor(rec, n);
      const mesh = this.meshes.get(shape)!;
      const i = mesh.count++;
      this.s.setScalar(k);
      this.v.set(p.x + dx, p.y, p.z);
      this.q.identity();
      mesh.setMatrixAt(i, this.m.compose(this.v, this.q, this.s));
      this.c.setHex(STATUS_COLOUR[status]);
      if (n.id === this.selected) this.c.lerp(WHITE, 0.25);
      mesh.setColorAt(i, this.c);

      const ci = this.collider.count++;
      this.s.setScalar(1);
      this.collider.setMatrixAt(ci, this.m.compose(this.v, this.q, this.s));
      this.drawnIds[ci] = n.id;

      // Glyphs on the face toward the eye (+z local).
      const r = (shape === 'hex' ? ROOT_R : NODE_R) * k;
      const gz = p.z + r + 0.002;
      if (status === 'COMPLETED' && shape !== 'disc') {
        const g = r * 0.5;
        this.seg(p.x - g, p.y, gz, p.x - g * 0.3, p.y - g * 0.7, gz, GLYPH, GLYPH);
        this.seg(p.x - g * 0.3, p.y - g * 0.7, gz, p.x + g, p.y + g * 0.8, gz, GLYPH, GLYPH);
      } else if (status === 'FAILED') {
        const g = r * 0.55;
        this.seg(p.x + dx - g, p.y - g, gz, p.x + dx + g, p.y + g, gz, GLYPH, GLYPH);
        this.seg(p.x + dx - g, p.y + g, gz, p.x + dx + g, p.y - g, gz, GLYPH, GLYPH);
      } else if (n.members) {
        // "+" on the disc: it holds N folded siblings; ray-select expands it.
        const g = r * 0.5;
        this.seg(p.x - g, p.y, gz, p.x + g, p.y, gz, GLYPH, GLYPH);
        this.seg(p.x, p.y - g, gz, p.x, p.y + g, gz, GLYPH, GLYPH);
      }
    }

    // Edges.
    for (const e of L.edges) {
      const a = this.pos.get(e.from);
      const b = this.pos.get(e.to);
      const na = L.nodes.find((x) => x.id === e.from);
      const nb = L.nodes.find((x) => x.id === e.to);
      if (!a || !b || !na || !nb || nb.layer < L.firstLayer) continue;
      const child = this.nodes.get(e.to);
      const base = e.kind === 'parent' ? EDGE_PARENT : e.kind === 'dep' ? EDGE_DEP : EDGE_RETRY;
      // Growth: the edge extends from the parent during the child's scale-in.
      let grow = 1;
      if (child) grow = Math.min(1, (now - child.bornAt) / SCALE_IN_MS);
      this.c.copy(base);
      if (child?.endedAt && e.kind === 'parent' && now - child.endedAt < FLASH_MS) {
        const t = 1 - (now - child.endedAt) / FLASH_MS;
        this.c.lerp(child.status === 'FAILED' ? EDGE_RETRY : WHITE, t);
      }
      // Below-window parents: draw from the base of the window.
      const ay = na.layer < L.firstLayer ? this.options.y0 - 0.03 : a.y;
      const bx = a.x + (b.x - a.x) * grow;
      const by = ay + (b.y - ay) * grow;
      const bz = a.z + (b.z - a.z) * grow;
      if (e.kind === 'dep') this.dashed(a.x, ay, a.z, bx, by, bz, this.c);
      else this.seg(a.x, ay, a.z, bx, by, bz, this.c, this.c);
    }

    // Selection ring.
    const sel = this.selected ? this.pos.get(this.selected) : undefined;
    this.ring.visible = !!sel;
    if (sel) {
      this.ring.position.copy(sel);
      this.ring.rotation.z = now * 0.001;
    }
    this.finish();
  }

  private finish(): void {
    for (const mesh of this.meshes.values()) {
      mesh.visible = mesh.count > 0;
      mesh.instanceMatrix.needsUpdate = true;
      if (mesh.instanceColor) mesh.instanceColor.needsUpdate = true;
    }
    this.collider.instanceMatrix.needsUpdate = true;
    this.collider.computeBoundingSphere();
    const g = this.lines.geometry;
    g.instanceCount = this.segs;
    for (const a of ['instanceStart', 'instanceColorStart']) {
      (g.attributes[a] as unknown as { data: { needsUpdate: boolean } }).data.needsUpdate = true;
    }
    this.lines.visible = this.segs > 0;
  }

  /** Lane guides at the back of the volume, plus one tick per node below the window. */
  private drawGuides(L: LayoutResult | null): void {
    const top = this.options.y0 + this.options.dy * (this.options.maxLayers - 1);
    const zb = -(this.options.zCentre + this.options.zHalf);
    this.options.lanes.forEach((lane, li) => {
      const x = this.options.laneX[li];
      this.seg(x, this.options.y0 - 0.04, zb, x, top + 0.04, zb, GUIDE, GUIDE);
      const hidden = Math.min(L?.hiddenByLane[lane] ?? 0, 12);
      for (let i = 0; i < hidden; i++) {
        const y = this.options.y0 - 0.05 - i * 0.006;
        const zc = -this.options.zCentre;
        this.seg(x - 0.05, y, zc, x + 0.05, y, zc, EDGE_PARENT, EDGE_PARENT);
      }
    });
  }

  private seg(
    ax: number, ay: number, az: number,
    bx: number, by: number, bz: number,
    ca: Color, cb: Color,
  ): void {
    if (this.segs >= MAX_SEGS) return;
    const o = this.segs++ * 6;
    const P = this.linePos;
    const C = this.lineCol;
    P[o] = ax; P[o + 1] = ay; P[o + 2] = az; P[o + 3] = bx; P[o + 4] = by; P[o + 5] = bz;
    C[o] = ca.r; C[o + 1] = ca.g; C[o + 2] = ca.b; C[o + 3] = cb.r; C[o + 4] = cb.g; C[o + 5] = cb.b;
  }

  /** Dashed segment: 12 mm dash, 8 mm gap, in the same LineSegments. */
  private dashed(ax: number, ay: number, az: number, bx: number, by: number, bz: number, c: Color): void {
    const len = Math.hypot(bx - ax, by - ay, bz - az);
    const n = Math.max(1, Math.floor(len / 0.02));
    for (let i = 0; i < n; i++) {
      const t0 = i / n;
      const t1 = t0 + 0.6 / n;
      this.seg(
        ax + (bx - ax) * t0, ay + (by - ay) * t0, az + (bz - az) * t0,
        ax + (bx - ax) * t1, ay + (by - ay) * t1, az + (bz - az) * t1,
        c, c,
      );
    }
  }
}

function aggStatus(view: GraphView, n: PlacedNode): Status {
  let best = RANK.length - 1;
  for (const id of n.members ?? []) {
    const r = view.nodes.get(id);
    if (r) best = Math.min(best, RANK.indexOf(r.status));
  }
  return RANK[best];
}

function easeOutBack(t: number): number {
  const c1 = 1.70158;
  const c3 = c1 + 1;
  return 1 + c3 * Math.pow(t - 1, 3) + c1 * Math.pow(t - 1, 2);
}
