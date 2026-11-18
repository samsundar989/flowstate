/**
 * Task tray (poke fallback for assignment, docs/design.md s3): poke a role-tagged card, then
 * poke "Assign here" on a panel. Roles are fixed: a wrong panel shows the hint line and the
 * card stays selected. A right panel sends task_assigned into the replay runtime.
 */
import { createSystem, UIKitMLAsset, Vector3 } from '@iwsdk/core';
import { deck } from './deck.js';
import { ROLES, skipLabel, type Skip } from './replay.js';
import { mix, T } from './theme.js';

type El = NonNullable<ReturnType<UIKitMLAsset['getElementById']>>;

const ROLE_NAME: Record<string, string> = {
  researcher: 'Researcher',
  builder: 'Builder',
  reviewer: 'Reviewer',
};
export const TASKS: Record<string, string> = {
  researcher: 'Survey how other repos test this',
  builder: 'Update the changelog',
  reviewer: 'Re-check the final diff',
};
const DEFAULT_HINT = 'Poke a card, then Assign on its panel';

/**
 * Press = pointerdown then pointerup on the same element. UIKit's synthetic `click` never
 * fired for hand poke or ray select at the 0.05 panel scale (down/up did), so we pair them.
 * Returns the cleanup function.
 */
export function onPress(el: El, fn: () => void): () => void {
  let armed = false;
  const down = () => (armed = true);
  const up = () => {
    if (!armed) return;
    armed = false;
    fn();
  };
  const leave = () => (armed = false);
  el.addEventListener('pointerdown', down);
  el.addEventListener('pointerup', up);
  el.addEventListener('pointerleave', leave);
  return () => {
    el.removeEventListener('pointerdown', down);
    el.removeEventListener('pointerup', up);
    el.removeEventListener('pointerleave', leave);
  };
}

/** Scrubber geometry in tray units (public/ui/tray.uikitml .t-scrub / .t-knob, pixel-size 0.005). */
const SCRUB_TRAVEL = 1152 - 6 - 64; // inner width minus the knob
const SCRUB_LOCAL = 1152 * 0.005; // bar width in the tray's local frame
const SCRUB_INNER = 1152 - 6; // inside the 3-unit border; absolute skip spans start at 0
/** Pre-declared collapsed-gap spans on the scrubber (tray.uikitml #scrub-skip-N). */
const SKIP_SPANS = 8;

let ui: {
  cards: Record<string, El>;
  hint: El;
  speed: El;
  mute: El;
} | null = null;
let onSelectionChange: (() => void) | null = null;

function paint(): void {
  if (!ui) return;
  for (const role of ROLES) {
    const done = deck.assigned.has(role);
    const sel = deck.selectedCard === role;
    ui.cards[role].setProperties({
      borderColor: sel ? T.ion : done ? mix(T.green, 0.5) : T.seam,
      backgroundColor: sel ? mix(T.ion, 0.14) : done ? mix(T.green, 0.1) : T.deck,
    } as never);
  }
  ui.speed.setProperties({ text: `${deck.speed}X` } as never);
  ui.mute.setProperties({ text: deck.muted ? 'MUTED' : 'SOUND' } as never);
  onSelectionChange?.();
}

function hint(text: string): void {
  ui?.hint.setProperties({ text } as never);
}

/** Poke on a panel's "Assign here". */
export function tryAssign(panelRole: string): void {
  const card = deck.selectedCard;
  if (!card) return;
  if (card !== panelRole) {
    const msg = `${ROLE_NAME[card]} cards go to the ${ROLE_NAME[card]}`;
    deck.info(`assign ${card} card on ${panelRole} panel rejected: ${msg}`);
    hint(msg);
    return;
  }
  deck.runtime?.assignTask(panelRole, TASKS[card], 'normal');
  deck.assigned.add(card);
  deck.selectedCard = null;
  hint(`Assigned to the ${ROLE_NAME[card]}`);
  paint();
}

export class TraySystem extends createSystem({}) {
  private doc!: UIKitMLAsset;
  private nextRefresh = 0;
  private skipsShown = -1;
  private dragging = false;
  private moved = false;
  private dragStart = 0;
  private fraction = 0;
  private point = new Vector3();
  init(): void {
    const doc = this.world.getSceneObject<UIKitMLAsset>('task-tray');
    if (!doc) {
      console.error('[overhand] task-tray missing from the scene');
      return;
    }
    this.doc = doc;
    const need = (id: string): El => {
      const e = doc.getElementById(id);
      if (!e) throw new Error(`[overhand] tray element #${id} missing`);
      return e;
    };
    const cards: Record<string, El> = {};
    for (const role of ROLES) {
      cards[role] = need(`card-${role}`);
      need(`card-${role}-task`).setProperties({ text: TASKS[role] } as never);
    }
    const scenario = need('scenario-button');
    const speed = need('speed-button');
    const mute = need('mute-button');
    ui = {
      cards,
      hint: need('hint-label'),
      speed: need('speed-label'),
      mute: need('mute-label'),
    };

    const on = (el: El, fn: () => void) => this.cleanupFuncs.push(onPress(el, fn));
    on(need('replay-night'), () => {
      if (deck.standup) {
        if (deck.standup.stage === 'intro') deck.practiceBegin();
        else if (deck.standup.stage === 'summary') deck.advanceTutorial();
        else if (deck.standup.stage === 'complete') { deck.standup.stage = 'replay'; deck.seek(0); }
        else if (deck.runtime) deck.runtime.paused = !deck.runtime.paused;
      } else void deck.load('real_autoflow_day');
    });
    on(need('projects-button'), () => deck.toggleProjects());
    on(need('tutorial-button'), () => {
      if (deck.standup) deck.replayTutorial();
      else void deck.load('real_autoflow_day').then(() => deck.replayTutorial());
    });
    on(need('skip-beat'), () => deck.advanceTutorial());
    const bar = need('scrubber');
    const xFor = (event: unknown): number | null => {
      const p = (event as { point?: Vector3 }).point;
      return p ? doc.worldToLocal(this.point.copy(p)).x : null;
    };
    const down = (e: unknown) => {
      const x = xFor(e);
      this.dragging = true; this.moved = false; this.dragStart = x ?? 0;
      const rt = deck.runtime;
      this.fraction = rt ? (rt.simTimeMs - rt.startMs) / (rt.endMs - rt.startMs) : 0;
    };
    const move = (e: unknown) => {
      const x = xFor(e);
      if (!this.dragging || x == null || Math.abs(x - this.dragStart) < 0.06) return;
      this.moved = true;
      this.fraction = Math.max(0, Math.min(1, this.fraction + (x - this.dragStart) / SCRUB_LOCAL));
      this.dragStart = x;
      need('scrub-fill').setProperties({ width: this.fraction * SCRUB_TRAVEL } as never);
    };
    const up = () => {
      if (!this.dragging) return;
      this.dragging = false;
      if (this.moved) deck.seek(this.fraction);
      else if (deck.runtime && deck.standup?.stage === 'replay') deck.runtime.paused = !deck.runtime.paused;
    };
    const cancel = () => { this.dragging = false; };
    bar.addEventListener('pointerdown', down);
    bar.addEventListener('pointermove', move);
    bar.addEventListener('pointerup', up);
    bar.addEventListener('pointercancel', cancel);
    bar.addEventListener('pointerleave', cancel); // as onPress: a release lost off the bar must not leave a drag armed
    this.cleanupFuncs.push(() => {
      bar.removeEventListener('pointerdown', down); bar.removeEventListener('pointermove', move);
      bar.removeEventListener('pointerup', up); bar.removeEventListener('pointercancel', cancel); bar.removeEventListener('pointerleave', cancel);
    });
    for (const role of ROLES) {
      on(cards[role], () => {
        if (deck.assigned.has(role)) return;
        deck.selectedCard = deck.selectedCard === role ? null : role;
        deck.info(`card ${role} ${deck.selectedCard ? 'selected' : 'deselected'}`);
        hint(deck.selectedCard ? `Now poke Assign on the ${ROLE_NAME[role]}` : DEFAULT_HINT);
        paint();
      });
    }
    on(scenario, () => void deck.nextFixture());
    on(speed, () => deck.toggleSpeed());
    on(mute, () => {
      deck.toggleMute();
      paint();
    });
    on(need('recentre-button'), () => window.dispatchEvent(new KeyboardEvent('keydown', { key: 'r' })));
    on(need('exit-button'), () => { void this.world.renderer.xr.getSession()?.end(); });
    deck.onReset(() => {
      this.skipsShown = -1;
      const portfolio = !!deck.runtime?.portfolio.stations.size;
      need('role-cards').setProperties({ display: portfolio ? 'none' : 'flex' } as never);
      hint(deck.standup ? 'Replaying changes nothing. Skipped questions come back.' : portfolio ? 'Pinch a station / poke an option' : DEFAULT_HINT);
      paint();
    });
    paint();
  }

  update(): void {
    const now = performance.now();
    if (now < this.nextRefresh || !this.doc) return;
    this.nextRefresh = now + 200;
    const rt = deck.runtime, s = deck.standup;
    this.doc.getElementById('standup-controls')?.setProperties({ display: s ? 'flex' : 'none' } as never);
    if (!s || !rt) return;
    if (s.tick(now)) { deck.info(`standup guided timeout stage=${s.stage}`); deck.syncStandup(); }
    this.doc.getElementById('skip-beat')?.setProperties({ display: ['summary', 'station', 'scrub', 'thumbs'].includes(s.stage) ? 'flex' : 'none' } as never);
    const ms = rt.simTimeMs;
    const clock = `${Math.floor(ms / 3600000).toString().padStart(2, '0')}:${Math.floor(ms / 60000 % 60).toString().padStart(2, '0')}:${Math.floor(ms / 1000 % 60).toString().padStart(2, '0')}`;
    const held = deck.practice.held ? 'RECEIPT HOLD' : rt.decisionHold ? 'DECISION HOLD' : s.stage.toUpperCase();
    const marked = rt.markedSkips, last: Skip | undefined = marked[marked.length - 1];
    this.doc.getElementById('standup-clock')?.setProperties({ text: `${clock} / ${deck.speed}X / ${rt.paused ? 'PAUSED' : held}${last ? ` / ${skipLabel(last)}` : ''}` } as never);
    // The recording's idle stretches, where they sit on the real clock: dim ahead, brighter once
    // the replay has collapsed them. Repainted only when that count changes.
    const passed = rt.idleGaps.filter(g => g.toMs <= ms).length;
    if (passed !== this.skipsShown) {
      this.skipsShown = passed;
      const longest = [...rt.idleGaps].sort((a, b) => (b.toMs - b.fromMs) - (a.toMs - a.fromMs)).slice(0, SKIP_SPANS);
      const span = rt.endMs - rt.startMs;
      for (let i = 0; i < SKIP_SPANS; i++) {
        const k = longest[i];
        this.doc.getElementById(`scrub-skip-${i}`)?.setProperties((k ? {
          display: 'flex',
          positionLeft: Math.round(SCRUB_INNER * (k.fromMs - rt.startMs) / span),
          width: Math.max(8, Math.round(SCRUB_INNER * (k.toMs - k.fromMs) / span)),
          backgroundColor: k.toMs <= ms ? T.ink2 : T.ink3,
        } : { display: 'none' }) as never);
      }
    }
    ui?.speed.setProperties({ text: `${deck.speed}X` } as never);
    deck.persist();
  }
}

/** The panel system shows/hides "Assign here" when the selection changes. */
export function setSelectionListener(fn: () => void): void {
  onSelectionChange = fn;
}
