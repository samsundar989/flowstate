import type { AgentEvent } from './replay.js';
import { STATION_ARC_M, STATION_SPACING_DEG, STATUS, type Status } from './theme.ts';

export interface Station {
  id: string;
  name: string;
  agents: Set<string>;
  metrics: number[];
  gate: string;
  deadline: number | null;
  detail: string;
  rows: Map<string, { name: string; status: string; task: string }>;
}

/** `markedDefault` is the recorded default_option; `default_option` falls back to the first option. */
export interface Decision extends AgentEvent { shownAt: number; order: number; markedDefault?: string }

/** Recorded briefs sometimes carry the whole option label or an ID followed by rationale. */
export function recommendedOption(d: AgentEvent): string | null {
  const exact = d.options?.find(o => o.id === d.recommendation || o.label === d.recommendation);
  if (exact) return exact.id;
  const prefix = /^([A-Za-z0-9]+)[.):\s]/u.exec(d.recommendation ?? '')?.[1];
  return d.options?.find(o => o.id === prefix)?.id ?? null;
}

/**
 * What a held thumbs-up answers: the recommendation, else (a malformed recommendation, e.g. the
 * real day's decision 9 "done" over option "1") the option the recording marked default, else
 * nothing (poke an option). Thumbs-down always snoozes and never comes here.
 */
export function thumbsUpOption(d: AgentEvent & { markedDefault?: string }): { id: string; via: 'recommendation' | 'default' } | null {
  const rec = recommendedOption(d);
  if (rec) return { id: rec, via: 'recommendation' };
  const marked = 'markedDefault' in d ? d.markedDefault : d.default_option;
  return d.options?.some(o => o.id === marked) ? { id: marked!, via: 'default' } : null;
}

const LEAD_ORDER = -0.5; // After the tutorial card (-1), before recorded cards (0).

/** Schema 1.1 state, independent of rendering and replay lane clocks. */
export class Portfolio {
  readonly stations = new Map<string, Station>();
  readonly decisions = new Map<string, Decision>();
  answered = 0;
  snoozed = 0;
  promoted: string | null = null;
  private order = 0;
  private frontKey = '';
  /** `lead`, a recorded decision id, sorts ahead of other unanswered cards; nothing else about it changes. */
  private readonly lead?: string;
  constructor(events: AgentEvent[], lead?: string) {
    this.lead = lead;
    for (const e of events) {
      if (!e.project) continue;
      let s = this.stations.get(e.project);
      if (!s) {
        s = { id: e.project, name: e.project_name ?? e.project, agents: new Set(),
          metrics: [], gate: '', deadline: null, detail: '', rows: new Map() };
        this.stations.set(e.project, s);
      }
      s.agents.add(e.agent_id);
      if (!s.rows.has(e.agent_id)) s.rows.set(e.agent_id, { name: e.agent_name ?? e.agent_id, status: 'SUBMITTED', task: '' });
    }
    if (this.stations.size && !this.stations.has('chief')) this.stations.set('chief', {
      id: 'chief', name: 'Chief', agents: new Set(), metrics: [],
      gate: 'Portfolio overview', deadline: null, detail: '', rows: new Map(),
    });
  }
  get queue(): Decision[] {
    return [...this.decisions.values()].sort((a, b) =>
      a.order - b.order || Number(!!b.blocking) - Number(!!a.blocking) ||
      (a.deadline_ms ?? Infinity) - (b.deadline_ms ?? Infinity) || a.t_ms - b.t_ms);
  }
  showFront(now: number, front?: Decision | null): Decision | undefined {
    const d = front === undefined ? this.queue[0] : front ?? undefined;
    const key = d ? `${d.decision_id}:${d.order}` : '';
    if (key !== this.frontKey) {
      this.frontKey = key;
      if (d) d.shownAt = now;
    }
    return d;
  }
  promote(id: string): void {
    if (this.stations.has(id)) this.promoted = this.promoted === id ? null : id;
  }
  snooze(id: string, now: number): boolean {
    const d = this.decisions.get(id);
    if (!d) return false;
    d.order = ++this.order;
    d.shownAt = now;
    this.snoozed++;
    return true;
  }
  apply(e: AgentEvent, now: number): void {
    const s = e.project ? this.stations.get(e.project) : undefined;
    const row = s?.rows.get(e.agent_id);
    if (row) {
      if (e.agent_name) row.name = e.agent_name;
      if (e.status) row.status = e.status;
      if (e.kind === 'task_assigned') row.task = e.title ?? '';
    }
    if (s && e.kind === 'metric' && typeof e.value === 'number') {
      s.metrics.push(e.value);
      if (s.metrics.length > 24) s.metrics.shift();
    }
    if (s && e.kind === 'message' && e.title === s.name) {
      s.detail = e.detail ?? '';
      const deadline = /deadline (\d+)/u.exec(s.detail);
      // Adapter snapshot deadlines are epoch seconds, unlike decision replay deadlines.
      if (deadline) s.deadline = Number(deadline[1]) * 1000;
    }
    if (s && e.kind === 'project') {
      s.gate = e.title ?? s.gate;
      s.deadline = e.deadline_ms ?? s.deadline;
    }
    if (e.kind === 'decision' && e.decision_id && e.status === 'INPUT_REQUIRED') {
      if (!this.decisions.has(e.decision_id)) this.decisions.set(e.decision_id, {
        ...e, shownAt: now, order: e.decision_id === this.lead ? LEAD_ORDER : 0, markedDefault: e.default_option,
        default_option: e.default_option ?? e.options?.[0]?.id,
      });
    } else if (e.decision_id && e.kind === 'input_response') {
      this.decisions.delete(e.decision_id);
    }
  }
}

/** Stable places in the indirect interaction band; no station enters the dead band. */
/** Index of the station nearest straight ahead (the one the guided beat promotes). */
export function centreStation(count: number): number {
  return Math.floor((count - 1) / 2);
}
export function stationPosition(index: number, count: number): [number, number, number] {
  const angle = (index - (count - 1) / 2) * STATION_SPACING_DEG * Math.PI / 180;
  return [Math.sin(angle) * STATION_ARC_M, -0.08, Math.cos(angle) * STATION_ARC_M];
}

/** Card centre height: its usual place, raised only as far as keeps its measured bottom above the option column. */
export function cardCentreY(base: number, columnTop: number, height: number, gap = 0.008): number {
  return Math.max(base, columnTop + gap + height / 2);
}

/**
 * Plate at depth z covering what the near column subtends. The near surfaces lie on a sphere of
 * radius `near`, so low and high edges sit closer than `near` and subtend more than their offset
 * at that distance would suggest: each edge is projected from its own depth.
 */
export function decisionBackdrop(c: { halfWidth: number; top: number; bottom: number; near: number; z: number; margin?: number }): { y: number; width: number; height: number } {
  const margin = c.margin ?? 0.008;
  const at = (y: number) => c.z / Math.sqrt(Math.max(0.01, c.near ** 2 - y ** 2));
  const top = c.top * at(c.top) + margin, bottom = c.bottom * at(c.bottom) - margin;
  const widest = Math.max(at(c.top), at(c.bottom));
  return { y: (top + bottom) / 2, width: 2 * c.halfWidth * widest + 2 * margin, height: top - bottom };
}

/** Stages where the card is the lesson; a station may not take its place. */
const GUIDED_STAGES = new Set(['summary', 'station', 'scrub', 'thumbs']);

export function guidedStage(stage?: string): boolean {
  return GUIDED_STAGES.has(stage ?? '');
}

/** The station `delta` places from `current` in arc order, wrapping; an unknown current starts at the first. */
export function steppedStation(ids: readonly string[], current: string | null, delta: 1 | -1): string | null {
  if (!ids.length) return null;
  const at = ids.indexOf(current ?? '');
  return at < 0 ? ids[0] : ids[(at + delta + ids.length) % ids.length];
}

/** Names the project in the near slot and its place in the set, so duplicate names stay distinct. */
export function projectPositionLabel(index: number, total: number, name: string): string {
  return `PROJECT ${index + 1} OF ${total} / ${name.toUpperCase()}`;
}

/** Where leaving the near station goes: the view it was opened over. */
export function returnLabel(inspecting: boolean, currentKey: string): string {
  if (!inspecting) return 'BACK TO ARC';
  return currentKey.startsWith('receipt:') ? 'BACK TO RECEIPT' : currentKey.startsWith('zero:') ? 'BACK TO SUMMARY' : 'BACK TO DECISION';
}

/**
 * Focus arbitration between the near card and a station brought here. The station wins only for
 * the exact card it was brought over (`inspectKey` is that card's key), so a new question always
 * takes the near slot back.
 */
export function stationInspecting(promoted: string | null, inspectKey: string, currentKey: string, inbox: boolean, stage?: string): boolean {
  return !!promoted && inbox && inspectKey === currentKey && !GUIDED_STAGES.has(stage ?? '');
}

/** Greedy word wrap: how many lines `text` takes at `perLine` characters. */
export function wrappedLines(text: string, perLine: number): number {
  return text.split('\n').reduce((total, part) => {
    let lines = 1, used = 0;
    for (const word of part.split(/\s+/u).filter(Boolean)) {
      if (used && used + 1 + word.length > perLine) { lines++; used = 0; }
      used += (used ? 1 : 0) + word.length;
      while (used > perLine) { lines++; used -= perLine; }
    }
    return total + lines;
  }, 0);
}

/** One card field as it wraps: `reserve` holds room for text a later state will show (a selection). */
export interface TextBlock { id: string; text: string; perLine: number; lineUnits: number; boxUnits?: number; split?: boolean; reserve?: string }
export type TextPage = { id: string; text: string }[];
const BLOCK_GAP_UNITS = 16;

/** The longest leading part of `text` that wraps to at most `lines` lines, cut only at whitespace unless one word is longer. */
function takeLines(text: string, perLine: number, lines: number): [string, string] {
  const tokens = text.match(/\S+\s*/gu) ?? [];
  let head = '', count = 0;
  for (const token of tokens) {
    if (wrappedLines(head + token.trimEnd(), perLine) > lines) break;
    head += token; count++;
  }
  if (!count) { const cut = Math.max(1, perLine * lines); return [text.slice(0, cut), text.slice(cut)]; }
  return [head.trimEnd(), tokens.slice(count).join('')];
}

/** Splits card fields into pages of at most `budget` units; every character of every field lands on exactly one page. */
export function paginateText(blocks: TextBlock[], budget: number): TextPage[] {
  const pages: TextPage[] = [[]];
  let used = 0;
  const last = () => pages[pages.length - 1];
  const flush = () => { pages.push([]); used = 0; };
  const height = (b: TextBlock, t: string) => wrappedLines(t, b.perLine) * b.lineUnits + (b.boxUnits ?? 0);
  for (const b of blocks) {
    let rest = b.text;
    for (;;) {
      const gap = last().length ? BLOCK_GAP_UNITS : 0;
      const need = Math.max(height(b, rest), b.reserve && rest === b.text ? height(b, b.reserve) : 0);
      if (used + gap + need <= budget) { last().push({ id: b.id, text: rest }); used += gap + need; break; }
      const room = Math.floor((budget - used - gap - (b.boxUnits ?? 0)) / b.lineUnits);
      if ((b.split || need > budget) && room >= 2) {
        const [head, tail] = takeLines(rest, b.perLine, room);
        last().push({ id: b.id, text: head });
        rest = tail;
        flush();
        continue;
      }
      if (!last().length) { last().push({ id: b.id, text: rest }); flush(); break; } // cannot be split further
      flush();
    }
  }
  if (!last().length && pages.length > 1) pages.pop();
  return pages;
}

/** The full gate line; no recorded gate gives no line. A gate that already says "next gate" takes no prefix. */
export function gateLabel(gate: string, days = ''): string {
  if (!gate.trim()) return '';
  return `${/^next gate/iu.test(gate) ? '' : 'GATE '}${gate.toUpperCase()}${days}`;
}

/** Characters per line in station.uikitml's 1040-unit content width (name sans 132, rows mono 80). */
const NAME_PER_LINE = 15;
const ROW_PER_LINE = 21;
const NAME_LINE_UNITS = 145;
const ROW_LINE_UNITS = 96;
const STATION_BASE_UNITS = 1180;
const STATION_ROW_UNITS = 110;

/** The station's one state: an open question, else a failure or stall, else all finished, else working. */
export function stationState(agents: { status: string }[], open: number, detail: string): Status {
  if (open) return 'INPUT_REQUIRED';
  if (agents.some(a => a.status === 'FAILED') || /stall [1-9]/u.test(detail)) return 'FAILED';
  return agents.length && agents.every(a => a.status === 'COMPLETED' || a.status === 'CANCELED') ? 'COMPLETED' : 'WORKING';
}

/** Station lines worded from the station's one state, one line per role (lead role first):
 *  a finished role keeps its own word; while a question is open only the asking role "needs you"
 *  and the rest are "on hold"; a stalled station's live roles are "on hold"; else they are working. */
export function stationRows(agents: { name: string; status: string }[], state: Status): string[] {
  const roles = new Map<string, string[]>();
  const lead = (n: string) => Number(/head|chief/iu.test(n));
  for (const a of [...agents].sort((a, b) => lead(b.name) - lead(a.name))) roles.set(a.name, [...(roles.get(a.name) ?? []), a.status]);
  const asking = [...roles].find(([, st]) => st.includes('INPUT_REQUIRED'))?.[0];
  let leadDone = false;
  return [...roles].map(([name, st]) => {
    const live = st.some(x => x !== 'COMPLETED' && x !== 'CANCELED' && x !== 'FAILED');
    let word: string;
    if (!live) word = STATUS[st.includes('FAILED') ? 'FAILED' : st.includes('COMPLETED') ? 'COMPLETED' : 'CANCELED'].row;
    else if (state === 'INPUT_REQUIRED') word = (asking ? name === asking : !leadDone) ? STATUS.INPUT_REQUIRED.row : 'on hold';
    else if (state === 'FAILED') word = 'on hold';
    else word = STATUS.WORKING.row;
    if (live) leadDone = true;
    return `${name}: ${word}`;
  });
}

/** Station root height: the one-line layout plus every extra line its full text wraps to. */
export function stationHeightUnits(name: string, gate: string, rows: string[]): number {
  const extra = (text: string, perLine: number) => wrappedLines(text, perLine) - 1;
  return STATION_BASE_UNITS + Math.max(1, rows.length) * STATION_ROW_UNITS
    + extra(name, NAME_PER_LINE) * NAME_LINE_UNITS + extra(gate, ROW_PER_LINE) * ROW_LINE_UNITS
    + rows.reduce((n, row) => n + extra(row, ROW_PER_LINE), 0) * ROW_LINE_UNITS;
}
