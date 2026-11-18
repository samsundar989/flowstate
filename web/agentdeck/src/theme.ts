/**
 * The one design system (task #110): autoflow's mission-control tokens, the status palette,
 * the type scale in degrees and the head-relative surface geometry. Every .uikitml class and
 * every three.js colour reads from here (the .uikitml files copy the hex values; the
 * `legibility` script checks the type sizes against SURFACES).
 *
 * Plain data only: scripts/legibility.mjs imports this file under node --experimental-strip-types.
 */

/** Colour tokens (autoflow ui/index.html :root). */
export const T = {
  void: '#070B10', // page / room background
  hull: '#0C1219', // panel slab
  deck: '#121A24', // panel face, tiles
  deckHi: '#182330', // hover / selected
  seam: '#1C2836', // 1 px borders
  seamHi: '#2B3B4F', // corner brackets, focus edges
  ink: '#E2EAF2', // primary text
  ink2: '#93A3B5', // secondary
  ink3: '#5A6A7C', // tertiary / micro-labels
  ion: '#5CE1E6', // accent: live, selected, links
  amber: '#FFB547', // needs you
  green: '#45D99A', // done / built
  red: '#FF6B6B', // failed
  violet: '#A894FF', // secondary / held
} as const;

/** `c` at `a` opacity over `bg` (the dashboard's rgba "-dim" tokens, flattened for uikit). */
export function mix(c: string, a: number, bg: string = T.deck): string {
  const p = (h: string, i: number) => parseInt(h.slice(1 + 2 * i, 3 + 2 * i), 16);
  let out = '#';
  for (let i = 0; i < 3; i++) {
    const v = Math.round(p(c, i) * a + p(bg, i) * (1 - a));
    out += v.toString(16).padStart(2, '0');
  }
  return out.toUpperCase();
}

export const hex = (c: string): number => parseInt(c.slice(1), 16);

export type Status = 'SUBMITTED' | 'WORKING' | 'INPUT_REQUIRED' | 'COMPLETED' | 'FAILED' | 'CANCELED';

/** Status -> colour (shape and text carry it too: colour is never the only cue). */
/** `row` is the plain word a station's agent line uses ("Chief: waiting"). */
export const STATUS: Record<Status, { c: string; dim: string; text: string; row: string }> = {
  SUBMITTED: { c: T.ink2, dim: mix(T.ink2, 0.12), text: 'QUEUED', row: 'waiting' },
  WORKING: { c: T.ion, dim: mix(T.ion, 0.14), text: 'WORKING', row: 'working' },
  INPUT_REQUIRED: { c: T.amber, dim: mix(T.amber, 0.16), text: 'NEEDS YOU', row: 'needs you' },
  COMPLETED: { c: T.green, dim: mix(T.green, 0.13), text: 'DONE', row: 'done' },
  FAILED: { c: T.red, dim: mix(T.red, 0.14), text: 'FAILED', row: 'stalled' },
  CANCELED: { c: T.ink3, dim: mix(T.ink3, 0.12), text: 'CANCELED', row: 'stopped' },
};

/** Type scale in degrees of visual angle (font-size em height at the surface's distance). */
export const TYPE_DEG = { floor: 1.0, label: 1.0, body: 1.2, heading: 1.7 } as const;

/** Design coordinates: origin = eye, +x right, +y up, +z forward (metres). */
export const LAYOUT: Record<string, [number, number, number]> = {
  'panel-researcher': [-0.25, -0.15, 0.32],
  'panel-builder': [0.0, -0.15, 0.4],
  'panel-reviewer': [0.25, -0.15, 0.32],
  // Tray (#203): centre 0.432 m so every poke target on it (corners included) sits in the
  // 0.42-0.46 m poke band; billboarded, so off-centre targets are slightly farther.
  'task-tray': [0.0, -0.32, 0.29],
};

/** Graph lift above the eye-relative layout (m): the 0.21 m panels' top edge sits at about
 *  -6 deg, so the root row (layout y0 -0.15 at 1.2 m = -7 deg) is raised to -2.4 deg. */
export const GRAPH_LIFT_M = 0.1;

/** Lowest node-card centre in graph-local metres: the 0.32 m card at z 1.0 keeps its bottom
 *  edge 2 cm above the panels' top sightline (-6 deg = -0.105 m from the eye, -0.205 local). */
export const CARD_MIN_Y = -0.025;

/** Node card: near face of the graph (z = 1.0), up to 0.47 m sideways and 0.4 m up. */
export const CARD_DISTANCE_M = 1.15;

/** Eye distance (m) of each placed UI surface, by scene node id. */
export const SURFACE_DISTANCE_M: Record<string, number> = {
  ...Object.fromEntries(Object.entries(LAYOUT).map(([k, p]) => [k, Math.hypot(...p)])),
  'node-card': CARD_DISTANCE_M,
};

/**
 * Surfaces stations.ts places in code (#111/#112, restyled #203). Node scale, eye distance, and
 * the poke band: the arc is read at 1.10 m (ray), the promoted station, decision card and its
 * options at 0.44 m (poke). The promoted station is drawn at 0.6x scale: its type reads
 * about 1.5x the angle it has on the arc. scripts/legibility.mjs scores every row.
 */
export const STATION_ARC_M = 1.1;
export const NEAR_M = 0.44;
export const STATION_SCALE = 0.05;
export const PROMOTED_SCALE = 0.03;
export const STATION_W_UNITS = 1120; // 0.28 m on the arc
export const STATION_SPACING_DEG = 17; // 8 stations span +-59.5 deg (design s2: +-60 seated)
export const PLACED_SURFACES = [
  { surface: 'station (arc)', file: 'station.uikitml', scale: STATION_SCALE, distance: STATION_ARC_M },
  { surface: 'station (promoted)', file: 'station.uikitml', scale: PROMOTED_SCALE, distance: NEAR_M },
  { surface: 'decision-card', file: 'decision.uikitml', scale: 0.05, distance: NEAR_M },
  { surface: 'decision-option', file: 'option.uikitml', scale: 0.05, distance: NEAR_M },
] as const;

/** uikit pixelSize on every root: 1 unit = pixelSize x node scale metres. */
export const PIXEL_SIZE = 0.005;

/** Graph hover / selection labels: mono, sized per frame to this angle. */
export const GRAPH_LABEL_DEG = 1.1;

/** XR framebuffer scale per headset (Quest 2 is the 60 fps floor). */
export function framebufferScaleFor(ua: string): number {
  if (/Quest 3/u.test(ua)) return 1.2; // Quest 3 / 3S
  return 1.0;
}
