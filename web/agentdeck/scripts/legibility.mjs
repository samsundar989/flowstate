// Legibility gate (task #110): every text class in public/ui/*.uikitml, sized in degrees of
// visual angle at the distance its surface sits from the eye.
//   node scripts/legibility.mjs        (from web/agentdeck; exit 1 if any class < 1.0 deg,
//                                        or any text element has no font-family)
// Size of a class = font-size (em) x root pixel-size x scene node scale, in metres; angle =
// 2 atan(size / 2d) with d from src/theme.ts (SURFACE_DISTANCE_M: LAYOUT for the panels and
// tray, CARD_DISTANCE_M for the node card; PLACED_SURFACES for the stations, decision card and
// option buttons src/stations.ts places in code; every public/ui file must be covered). The graph's runtime labels are sized per frame to
// GRAPH_LABEL_DEG and are listed too.
import { spawnSync } from 'node:child_process';
import { readdirSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

let theme;
try {
  theme = await import('../src/theme.ts');
} catch {
  // Node < 22.18 needs the flag to import TypeScript: re-run with it.
  if (process.env.LEGIBILITY_CHILD) throw new Error('cannot import src/theme.ts');
  const r = spawnSync(
    process.execPath,
    ['--experimental-strip-types', '--no-warnings', fileURLToPath(import.meta.url)],
    { stdio: 'inherit', env: { ...process.env, LEGIBILITY_CHILD: '1' } },
  );
  process.exit(r.status ?? 1);
}
const { SURFACE_DISTANCE_M, TYPE_DEG, GRAPH_LABEL_DEG, PLACED_SURFACES } = theme;

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, '..');
const scene = JSON.parse(readFileSync(join(root, 'public/scenes/main.iwsdk.scene.json'), 'utf8'));
const assetsSrc = readFileSync(join(root, 'src/assets.ts'), 'utf8');
const assetFile = Object.fromEntries(
  [...assetsSrc.matchAll(/'([\w-]+)':\s*uikitml\('([\w.-]+)'/gu)].map((m) => [m[1], m[2]]),
);

const deg = (size, d) => (2 * Math.atan(size / 2 / d) * 180) / Math.PI;
const num = (v) => (v == null ? undefined : parseFloat(String(v)));

/** Parse a .uikitml <style> block into { className: { prop: value } }. */
function styles(src) {
  const css = /<style>([\s\S]*?)<\/style>/u.exec(src)?.[1] ?? '';
  const out = {};
  for (const m of css.matchAll(/\.([\w-]+)(:[\w-]+)?\s*\{([^}]*)\}/gu)) {
    if (m[2]) continue; // :hover / :active do not change type size
    const props = (out[m[1]] ??= {});
    for (const d of m[3].split(';')) {
      const i = d.indexOf(':');
      if (i > 0) props[d.slice(0, i).trim()] = d.slice(i + 1).trim();
    }
  }
  return out;
}

let bad = 0;
const rows = [];
const seen = new Set();
// Scene-placed surfaces (distance from LAYOUT / CARD_DISTANCE_M) plus the surfaces
// src/stations.ts places in code (PLACED_SURFACES: arc 1.10 m, promoted / decision 0.44 m).
const surfaces = [
  ...scene.nodes.flatMap((node) => {
    const file = node.content?.asset && assetFile[node.content.asset];
    if (!file) return [];
    const scale = node.transform?.scale ?? 1;
    return [{ id: node.id, file, scale: Array.isArray(scale) ? scale[0] : scale, d: SURFACE_DISTANCE_M[node.id] }];
  }),
  ...PLACED_SURFACES.map((p) => ({ id: p.surface, file: p.file, scale: p.scale, d: p.distance })),
];
const covered = new Set(surfaces.map((x) => x.file));
for (const f of readdirSync(join(root, 'public/ui')).filter((n) => n.endsWith('.uikitml'))) {
  if (!covered.has(f)) {
    console.log(`FAIL ${f}: not placed in the scene or listed in src/theme.ts PLACED_SURFACES`);
    bad++;
  }
}
for (const node of surfaces) {
  const file = node.file;
  const d = node.d;
  if (!d) {
    console.log(`FAIL ${node.id}: no eye distance in src/theme.ts SURFACE_DISTANCE_M`);
    bad++;
    continue;
  }
  const src = readFileSync(join(root, 'public/ui', file), 'utf8');
  const cls = styles(src);
  const body = src.replace(/<style>[\s\S]*?<\/style>/u, '').replace(/<!--[\s\S]*?-->/gu, '');
  // Root = first element; its classes carry pixel-size.
  const rootTag = /<\w+[^>]*class="([^"]+)"/u.exec(body);
  const rootCls = rootTag ? rootTag[1].split(/\s+/u) : [];
  const px = num(rootCls.map((c) => cls[c]?.['pixel-size']).find(Boolean)) ?? 0.01;
  const unitM = px * node.scale;

  // Text elements: an element whose direct content is non-whitespace text.
  for (const m of body.matchAll(/<(\w+)([^>]*)>([^<]*)(?=<)/gu)) {
    if (!m[3].trim()) continue;
    const attrs = m[2];
    const classes = (/class="([^"]+)"/u.exec(attrs)?.[1] ?? '').split(/\s+/u).filter(Boolean);
    const inline = /style="([^"]*)"/u.exec(attrs)?.[1] ?? '';
    const pick = (prop) => {
      const im = new RegExp(`${prop}\\s*:\\s*([^;]+)`, 'u').exec(inline);
      if (im) return im[1].trim();
      let v;
      for (const c of classes) if (cls[c]?.[prop] != null) v = cls[c][prop];
      return v;
    };
    const family = pick('font-family');
    const fs = num(pick('font-size'));
    const id = /id="([^"]+)"/u.exec(attrs)?.[1] ?? m[3].trim().slice(0, 16);
    if (!family) {
      console.log(`FAIL ${file} <${m[1]}> "${id}": no font-family`);
      bad++;
    }
    if (fs == null) {
      console.log(`FAIL ${file} <${m[1]}> "${id}": no font-size`);
      bad++;
      continue;
    }
    const key = `${node.id}|${classes.join('.')}`;
    if (seen.has(key)) continue;
    seen.add(key);
    const a = deg(fs * unitM, d);
    const role = fs * unitM * 1000 >= 9 && a >= TYPE_DEG.body ? 'body' : 'label';
    rows.push({
      surface: node.id,
      cls: classes.join('.'),
      family: `${family ?? '?'}${pick('font-weight') === '600' ? ' 600' : ''}`,
      units: fs,
      mm: +(fs * unitM * 1000).toFixed(1),
      dist: +d.toFixed(3),
      deg: +a.toFixed(2),
      role,
    });
    if (a < TYPE_DEG.floor) bad++;
  }
}
for (const d of [1.0, 1.4]) {
  rows.push({
    surface: 'graph-label',
    cls: '(runtime)',
    family: 'mono 600',
    units: '-',
    mm: +(2 * d * Math.tan((GRAPH_LABEL_DEG * Math.PI) / 360) * 1000).toFixed(1),
    dist: d,
    deg: GRAPH_LABEL_DEG,
    role: 'label',
  });
}
console.table(rows);
const low = rows.filter((r) => r.deg < TYPE_DEG.floor);
for (const r of low) console.log(`FAIL ${r.surface} .${r.cls}: ${r.deg} deg < ${TYPE_DEG.floor}`);
const min = Math.min(...rows.map((r) => r.deg));
console.log(`${rows.length} text classes, min ${min.toFixed(2)} deg, floor ${TYPE_DEG.floor} deg`);
if (bad) {
  console.log(`legibility: ${bad} failure(s)`);
  process.exit(1);
}
console.log('legibility: ok');
