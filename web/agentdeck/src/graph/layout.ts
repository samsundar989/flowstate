/**
 * Layered layout for the execution graph (docs/design.md s5). Pure TypeScript, no three.js, so
 * it runs under `node --test`.
 *
 * - Layering: longest path over parent + depends_on edges (cross-agent edges push layers up).
 * - Lanes: one per agent at a fixed x; the graph grows upward, layer k at y0 + dy * k, and only
 *   the newest `maxLayers` layers are shown (older ones are counted per lane, not drawn).
 * - Sibling collapse: more than `collapseOver` children of one parent in one layer keep the
 *   newest `collapseOver - 1` and fold the older ones into a single "+N" node, unless the
 *   parent is in `expanded`.
 * - Ordering: one top-down barycentric sweep per lane-layer (mean slot of the parents), ties
 *   by arrival order. Slots fill a grid: up to 4 columns in x (+-xHalf), extra rows back in z.
 * Coordinates are design coordinates: metres, origin at the eye, +x right, +y up, +z forward.
 */

export interface GraphNodeIn {
  id: string;
  lane: string;
  parent: string | null;
  deps: string[];
  retryOf?: string;
}

export type EdgeKind = 'parent' | 'dep' | 'retry';

export interface PlacedNode {
  id: string;
  lane: string;
  layer: number;
  x: number;
  y: number;
  z: number;
  /** Ids folded into this "+N" node; undefined for ordinary nodes. */
  members?: string[];
  /** Parent id of a "+N" node (expand key). */
  groupOf?: string;
}

export interface PlacedEdge {
  from: string;
  to: string;
  kind: EdgeKind;
}

export interface LayoutResult {
  nodes: PlacedNode[];
  edges: PlacedEdge[];
  /** Maps every input id to the drawn node id (itself, or its "+N" node). */
  drawnAs: Map<string, string>;
  firstLayer: number;
  maxLayer: number;
  /** Nodes below the visible window, per lane. */
  hiddenByLane: Record<string, number>;
}

export interface LayoutOptions {
  lanes: string[];
  laneX: number[];
  y0: number;
  dy: number;
  maxLayers: number;
  zCentre: number;
  zHalf: number;
  /** Half-width of a lane's grid in x (outer column centres). */
  xHalf: number;
  maxCols: number;
  /** Depth step between rows of one lane-layer. */
  zStep: number;
  collapseOver: number;
  expanded?: ReadonlySet<string>;
}

export const DEFAULT_LAYOUT: LayoutOptions = {
  lanes: ['researcher', 'builder', 'reviewer'],
  laneX: [-0.27, 0, 0.27],
  y0: -0.15,
  dy: 0.07,
  maxLayers: 8,
  zCentre: 1.2,
  zHalf: 0.15,
  xHalf: 0.1,
  maxCols: 4,
  zStep: 0.09,
  collapseOver: 4,
};

export const AGG_PREFIX = '+';

export function layoutGraph(
  input: readonly GraphNodeIn[],
  opts: LayoutOptions = DEFAULT_LAYOUT,
): LayoutResult {
  const byId = new Map<string, GraphNodeIn>();
  const order = new Map<string, number>();
  for (const n of input) {
    if (byId.has(n.id)) continue;
    byId.set(n.id, n);
    order.set(n.id, order.size);
  }
  const preds = (n: GraphNodeIn): string[] => {
    const p = n.parent && byId.has(n.parent) ? [n.parent] : [];
    for (const d of n.deps) if (byId.has(d) && d !== n.parent) p.push(d);
    return p;
  };

  // 1. Longest-path layering (iterative DFS, cycle-safe: a back edge counts as layer 0).
  const layer = new Map<string, number>();
  const visiting = new Set<string>();
  const layerOf = (id: string): number => {
    const known = layer.get(id);
    if (known !== undefined) return known;
    const stack: string[] = [id];
    while (stack.length) {
      const cur = stack[stack.length - 1];
      if (layer.has(cur)) {
        stack.pop();
        continue;
      }
      visiting.add(cur);
      const ps = preds(byId.get(cur)!);
      const todo = ps.filter((p) => !layer.has(p) && !visiting.has(p));
      if (todo.length) {
        stack.push(...todo);
        continue;
      }
      let l = 0;
      for (const p of ps) {
        const pl = layer.get(p);
        if (pl !== undefined) l = Math.max(l, pl + 1);
      }
      layer.set(cur, l);
      visiting.delete(cur);
      stack.pop();
    }
    return layer.get(id)!;
  };
  for (const id of byId.keys()) layerOf(id);

  // 2. Sibling collapse per (parent, layer).
  const drawnAs = new Map<string, string>();
  const groups = new Map<string, string[]>();
  for (const n of byId.values()) {
    if (!n.parent || !byId.has(n.parent)) continue;
    const key = `${n.parent}|${layer.get(n.id)}`;
    const g = groups.get(key);
    if (g) g.push(n.id);
    else groups.set(key, [n.id]);
  }
  const aggregates: PlacedNode[] = [];
  for (const [key, ids] of groups) {
    const parent = key.slice(0, key.lastIndexOf('|'));
    if (ids.length <= opts.collapseOver || opts.expanded?.has(parent)) continue;
    const folded = ids.slice(0, ids.length - (opts.collapseOver - 1));
    const aggId = `${AGG_PREFIX}${folded.length}:${parent}@${layer.get(ids[0])}`;
    for (const id of folded) drawnAs.set(id, aggId);
    const first = byId.get(folded[0])!;
    aggregates.push({
      id: aggId,
      lane: first.lane,
      layer: layer.get(first.id)!,
      x: 0,
      y: 0,
      z: 0,
      members: folded,
      groupOf: parent,
    });
    order.set(aggId, order.get(first.id)!);
    layer.set(aggId, layer.get(first.id)!);
  }
  for (const id of byId.keys()) if (!drawnAs.has(id)) drawnAs.set(id, id);

  // 3. Drawn nodes and deduplicated edges.
  const nodes: PlacedNode[] = [];
  for (const n of byId.values()) {
    if (drawnAs.get(n.id) !== n.id) continue;
    nodes.push({ id: n.id, lane: n.lane, layer: layer.get(n.id)!, x: 0, y: 0, z: 0 });
  }
  nodes.push(...aggregates);
  const edges: PlacedEdge[] = [];
  const seen = new Set<string>();
  const addEdge = (a: string, b: string, kind: EdgeKind) => {
    const from = drawnAs.get(a);
    const to = drawnAs.get(b);
    if (!from || !to || from === to) return;
    const k = `${from}>${to}`;
    if (seen.has(k)) return;
    seen.add(k);
    edges.push({ from, to, kind });
  };
  for (const n of byId.values()) {
    if (n.parent) addEdge(n.parent, n.id, 'parent');
    if (n.retryOf) addEdge(n.retryOf, n.id, 'retry');
  }
  for (const n of byId.values()) for (const d of n.deps) addEdge(d, n.id, 'dep');

  // 4. Visible window.
  let maxLayer = 0;
  for (const n of nodes) maxLayer = Math.max(maxLayer, n.layer);
  const firstLayer = Math.max(0, maxLayer - opts.maxLayers + 1);
  const hiddenByLane: Record<string, number> = {};
  for (const lane of opts.lanes) hiddenByLane[lane] = 0;

  // 5. Barycentric ordering, layer by layer (bottom up = parents first), then grid slots.
  const inEdges = new Map<string, string[]>();
  for (const e of edges) {
    const l = inEdges.get(e.to);
    if (l) l.push(e.from);
    else inEdges.set(e.to, [e.from]);
  }
  const slotKey = new Map<string, number>(); // normalised position in [-1, 1] within its lane-layer
  const cells = new Map<string, PlacedNode[]>();
  for (const n of nodes) {
    const k = `${n.lane}|${n.layer}`;
    const c = cells.get(k);
    if (c) c.push(n);
    else cells.set(k, [n]);
  }
  const laneIndex = new Map(opts.lanes.map((l, i) => [l, i]));
  const cellKeys = [...cells.keys()].sort(
    (a, b) => Number(a.split('|').pop()) - Number(b.split('|').pop()),
  );
  for (const k of cellKeys) {
    const cell = cells.get(k)!;
    const bary = new Map<string, number>();
    for (const n of cell) {
      const ps = (inEdges.get(n.id) ?? []).filter((p) => slotKey.has(p));
      bary.set(
        n.id,
        ps.length ? ps.reduce((s, p) => s + slotKey.get(p)!, 0) / ps.length : 0,
      );
    }
    cell.sort(
      (a, b) => bary.get(a.id)! - bary.get(b.id)! || order.get(a.id)! - order.get(b.id)!,
    );
    // Siblings spread across the lane in x first (seen from the seat, z-only spreads stack
    // nodes behind each other and the front one takes every ray), then back in z.
    const count = cell.length;
    const cols = Math.min(count, opts.maxCols);
    const rows = Math.ceil(count / cols);
    const xStep = cols > 1 ? (2 * opts.xHalf) / (opts.maxCols - 1) : 0;
    const zStep = rows > 1 ? Math.min(opts.zStep, (2 * opts.zHalf) / (rows - 1)) : 0;
    const li = laneIndex.get(cell[0].lane);
    const laneX = li === undefined ? 0 : opts.laneX[li];
    cell.forEach((n, i) => {
      const row = Math.floor(i / cols);
      const inRow = Math.min(cols, count - row * cols);
      const col = i % cols;
      slotKey.set(n.id, count > 1 ? (2 * i) / (count - 1) - 1 : 0);
      n.x = laneX + (col - (inRow - 1) / 2) * xStep;
      n.z = opts.zCentre + (row - (rows - 1) / 2) * zStep;
      if (n.layer < firstLayer) {
        n.y = opts.y0 - opts.dy;
        hiddenByLane[n.lane] = (hiddenByLane[n.lane] ?? 0) + 1;
      } else {
        n.y = opts.y0 + opts.dy * (n.layer - firstLayer);
      }
    });
  }

  return { nodes, edges, drawnAs, firstLayer, maxLayer, hiddenByLane };
}

/** True when a node is inside the visible window. */
export function isVisible(n: PlacedNode, r: LayoutResult): boolean {
  return n.layer >= r.firstLayer;
}
