/**
 * Three agent panels (docs/design.md s2, s5): layout at 0.44 m / +-38 deg from the eye,
 * status chips in the theme's status colours (src/theme.ts), Approve / Stop live only during INPUT_REQUIRED,
 * "Assign here" while a tray card is selected. Also drives the replay clock, the room
 * placement, the held thumbs-up / thumbs-down answers (src/gesture.ts) and the sound cues.
 */
import {
  BoxGeometry,
  Color,
  createSystem,
  Mesh,
  MeshStandardMaterial,
  Object3D,
  UIKitMLAsset,
  Vector3,
  VisibilityState,
} from '@iwsdk/core';
import { deck } from './deck.js';
import { thumbsUpOption } from './portfolio.js';
import {
  classifyThumb,
  optionFor,
  questionKey,
  ThumbGate,
  type Joints,
  type Thumb,
  type Vec3,
} from './gesture.js';
import { ROLES, type AgentState, type Status } from './replay.js';
import { loadRoom, placeRoom } from './room.js';
import { cueFor } from './cues.js';
import { Sounds } from './sound.js';
import { LAYOUT, mix, PIXEL_SIZE, STATUS, T } from './theme.js';
import { onPress, setSelectionListener, tryAssign } from './tray.js';

/** Status chip: status-colour border and text on its dim fill (dashboard chips). */
export const CHIP = Object.fromEntries(
  Object.entries(STATUS).map(([k, v]) => [k, { border: v.c, bg: v.dim, fg: v.c, text: v.text }]),
) as Record<Status, { border: string; bg: string; fg: string; text: string }>;
const ROW_MARK: Record<Status, string> = {
  SUBMITTED: ' .  ',
  WORKING: '>>  ',
  INPUT_REQUIRED: '??  ',
  COMPLETED: 'ok  ',
  FAILED: ' x  ',
  CANCELED: '--  ',
};

export { LAYOUT };
const RISE_M = 0.03;
const JOINTS = [
  'wrist',
  'thumb-phalanx-proximal',
  'thumb-tip',
  ...['index', 'middle', 'ring', 'pinky'].flatMap((f) => [
    `${f}-finger-phalanx-proximal`,
    `${f}-finger-tip`,
  ]),
];
const HIGHLIGHT_MS = 6000;
/** Panel face in uikit units (public/ui/panel.uikitml .root) and the slab behind it. */
const FACE_W = 960;
const FACE_H = 830;
const SLAB_MARGIN_M = 0.0025; // the slab shows 2.5 mm around the face: the emissive edge
const SLAB_DEPTH_M = 0.008;
const STEP_ROWS = 5;
/** Button styles while live (dashboard .btn.primary: tinted border, coloured label). */
const BTN = {
  approve: { border: mix(T.amber, 0.5), fg: T.amber },
  stop: { border: mix(T.red, 0.5), fg: T.red },
  assign: { border: mix(T.ion, 0.5), fg: T.ion },
} as const;
const EDGE_ASK = new Color(T.amber);
const EDGE_SEL = new Color(T.ion);
const EDGE_OFF = new Color(0x000000);

/** "Replayed from a real coding-agent run - REPLAY 4x" -> "REAL RUN - 4X" (fits the strip). */
const modeTag = (line: string) =>
  line
    .replace('Replayed from a real coding-agent run', 'REAL RUN')
    .replace('Scripted scenario', 'SCRIPTED')
    .replace(' - REPLAY ', ' - ')
    .toUpperCase();
const clip = (s: string, n: number) => (s.length > n ? `${s.slice(0, n - 3)}...` : s);
// The MSDF atlases carry ASCII only (+ a few dashes): keep runtime copy ASCII.
const ascii = (s: string) => s.replace(/[^\x20-\x7E]/gu, '-');

type El = NonNullable<ReturnType<UIKitMLAsset['getElementById']>>;
interface PanelView {
  root: Object3D;
  el: Record<string, El>;
  base: Vector3;
  raised: boolean;
  /** last value written to the buttons' disabled prop (rewriting it mid-press drops the press) */
  enabled: boolean | null;
  /** performance.now() until which the "Jump to agent" highlight shows; 0 = off */
  highlightUntil: number;
  slab: MeshStandardMaterial;
  asking: boolean;
  /** last hold-progress width written (tenths) */
  hold: number;
}

export class PanelSystem extends createSystem({}) {
  private views = new Map<string, PanelView>();
  private eye = new Vector3();
  private fwd = new Vector3();
  private tmp = new Vector3();
  private recentreIn = -1;
  private nextCountdown = 0;
  private gate = new ThumbGate();
  private joints: Joints = Object.fromEntries(JOINTS.map((n) => [n, [0, 0, 0] as Vec3]));
  private lastPose: Thumb | null = null;
  private sounds: Sounds | null = null;

  init(): void {
    for (const role of ROLES) {
      const doc = this.world.getSceneObject<UIKitMLAsset>(`panel-${role}`);
      if (!doc) {
        console.error(`[overhand] panel-${role} missing from the scene`);
        continue;
      }
      const el: Record<string, El> = {};
      for (const id of [
        'role-label',
        'status-chip',
        'status-text',
        'mode-label',
        'current-label',
        'note-label',
        'approve-button',
        'stop-button',
        'assign-button',
        'step-0',
        'step-1',
        'step-2',
        'step-3',
        'step-4',
        'jump-bar',
        'gesture-hint',
        'panel-root',
        'strip-label',
        'hold-track',
        'hold-fill',
        'approve-label',
        'approve-icon',
        'stop-label',
        'stop-icon',
      ]) {
        const e = doc.getElementById(id);
        if (e) el[id] = e;
      }
      const on = (id: string, fn: () => void) => this.cleanupFuncs.push(onPress(el[id], fn));
      on('approve-button', () => this.respond(role, 'up', 'poke'));
      on('stop-button', () => this.respond(role, 'down', 'poke'));
      on('assign-button', () => tryAssign(role));
      const root = this.world.getSceneEntity(`panel-${role}`)?.object3D ?? doc;
      this.views.set(role, {
        root,
        el,
        base: new Vector3(),
        raised: false,
        enabled: null,
        highlightUntil: 0,
        slab: this.addSlab(root),
        asking: false,
        hold: -1,
      });
    }

    this.sounds = new Sounds(this.world.camera, (id) => this.views.get(id)?.root ?? null);
    deck.onEvent((e) => {
      if (e.project || e.decision_id) return;
      const cue = cueFor(e);
      if (cue) this.sounds?.play(cue, e.agent_id);
    });
    void loadRoom(this.world).catch((err) =>
      console.warn(`[overhand] room failed to load: ${String(err)}`),
    );
    deck.onState((a) => this.render(a));
    deck.onReset(() => {
      this.gate = new ThumbGate();
      const portfolio = !!deck.runtime?.portfolio.stations.size;
      for (const v of this.views.values()) {
        v.root.visible = !portfolio;
        if (portfolio) v.root.position.y = -50;
      }
      if (!portfolio) this.recentre();
      this.renderAssign();
    });
    setSelectionListener(() => this.renderAssign());
    deck.onHighlight((role) => {
      const v = this.views.get(role);
      if (!v) return;
      v.highlightUntil = performance.now() + HIGHLIGHT_MS;
      v.el['jump-bar']?.setProperties({ display: 'flex' } as never);
      this.paintEdge(v);
    });
    this.cleanupFuncs.push(
      this.world.visibilityState.subscribe((v) => {
        // Re-pose to the head shortly after entering XR (the first XR frames report identity).
        if (v !== VisibilityState.NonImmersive) {
          this.recentreIn = 30;
          this.sounds?.resume();
        }
      }),
    );
    this.recentre();
    const key = (e: KeyboardEvent) => { if (e.key === 'r') this.recentre(); };
    window.addEventListener('keydown', key);
    this.cleanupFuncs.push(() => window.removeEventListener('keydown', key));
    void deck.load(deck.fixture);
  }

  update(): void {
    const rt = deck.runtime;
    const now = performance.now();
    rt?.tick(now);
    if (this.recentreIn > 0 && --this.recentreIn === 0) this.recentre();
    for (const v of this.views.values()) {
      if (v.highlightUntil && now >= v.highlightUntil) {
        v.highlightUntil = 0;
        v.el['jump-bar']?.setProperties({ display: 'none' } as never);
        this.paintEdge(v);
      }
    }
    if (rt) this.updateGesture(now);
    if (rt && now >= this.nextCountdown) {
      this.nextCountdown = now + 1000;
      for (const a of rt.agents.values()) if (a.question) this.render(a);
    }
  }

  /**
   * Approve / thumbs-up = the option tagged thumbs_up (else the first option);
   * Stop / thumbs-down = the option tagged thumbs_down (else the first other option).
   */
  private respond(role: string, kind: Thumb, how: 'poke' | 'gesture'): void {
    const rt = deck.runtime;
    const q = rt?.pending(role);
    if (!rt || !q) return; // buttons are disabled outside INPUT_REQUIRED; ignore stray hits
    const opt = optionFor(q, kind)?.id ?? q.default_option;
    const what = how === 'poke' ? (kind === 'up' ? 'approve' : 'stop') : `thumbs-${kind}`;
    deck.info(`${how} ${what} on ${role} -> ${opt}`);
    rt.answer(role, opt, 'user');
  }

  /** Classify both hands from XR joints; a held pose answers the oldest pending question. */
  private updateGesture(now: number): void {
    const rt = deck.runtime!;
    const decision = rt.portfolio.showFront(now, deck.front(now) ?? null);
    let target: AgentState | null = null;
    for (const a of rt.agents.values()) {
      if (a.question && (!target || a.question.askedAtReal < target.question!.askedAtReal))
        target = a;
    }
    let pose: Thumb | null = null;
    const xr = this.world.renderer.xr;
    const frame = xr.isPresenting ? xr.getFrame() : null;
    const ref = xr.getReferenceSpace();
    if (frame && ref && (target || decision)) {
      for (const src of frame.session.inputSources) {
        if (!src.hand || pose) continue;
        let ok = true;
        for (const name of JOINTS) {
          const js = src.hand.get(name as XRHandJoint);
          const p = js ? frame.getJointPose?.(js, ref) : undefined;
          if (!p) {
            ok = false;
            break;
          }
          const v = this.joints[name];
          v[0] = p.transform.position.x;
          v[1] = p.transform.position.y;
          v[2] = p.transform.position.z;
        }
        if (ok) pose = classifyThumb(this.joints);
      }
    }
    if (pose !== this.lastPose) {
      if (pose) deck.info(`pose thumbs-${pose} seen`);
      this.lastPose = pose;
    }
    const q = target?.question;
    const fired = this.gate.update(
      deck.inspecting ? null : pose,
      now,
      decision ? { key: `decision:${decision.decision_id}:${decision.order}`, shownAtMs: decision.shownAt } :
        target && q ? { key: questionKey(target.id, q), shownAtMs: q.askedAtReal } : null,
    );
    deck.hold.progress = decision && pose ? this.gate.progress : 0;
    deck.hold.pose = pose;
    if (fired && decision && deck.standup && decision.decision_id !== 'tutorial') {
      // Recorded practice: thumbs only confirm what was explicitly selected, or skip without saving.
      if (fired === 'down') deck.practiceSkip(now, 'gesture');
      else if (!deck.practiceConfirm(now, 'gesture')) this.gate.release(`decision:${decision.decision_id}:${decision.order}`);
    } else if (fired && decision) {
      if (fired === 'down') {
          rt.snoozeDecision(decision.decision_id!, now);
        deck.info(`gesture thumbs-down snooze decision_id=${decision.decision_id}`);
      } else {
        const option = thumbsUpOption(decision);
        if (option) {
          deck.info(`gesture thumbs-up decision_id=${decision.decision_id} -> ${option.id} (${option.via})`);
          rt.answerDecision(decision.decision_id!, option.id);
        } else {
          this.gate.release(`decision:${decision.decision_id}:${decision.order}`);
          deck.info(`decision_id=${decision.decision_id}: recommendation has no matching option and no marked default; poke required`);
        }
      }
    } else if (fired && target) this.respond(target.id, fired, 'gesture');
    // Hold progress (dashboard .prog bar) on the panel the held pose would answer.
    for (const [id, v] of this.views) {
      const tenths = target?.id === id && pose ? Math.round(this.gate.progress * 10) : 0;
      if (tenths === v.hold) continue;
      v.hold = tenths;
      v.el['hold-fill']?.setProperties({
        width: `${tenths * 10}%`,
        backgroundColor: pose === 'down' ? T.red : T.amber,
      } as never);
    }
  }

  /**
   * A lit slab behind the (unlit, token-exact) uikit face: thickness the key and rim lights
   * catch; its 2.5 mm rim glows amber while the agent asks and ion while highlighted.
   */
  private addSlab(root: Object3D): MeshStandardMaterial {
    const s = root.scale.x || 1; // the slab lives in the panel's scaled local frame
    const w = FACE_W * PIXEL_SIZE + (2 * SLAB_MARGIN_M) / s;
    const h = FACE_H * PIXEL_SIZE + (2 * SLAB_MARGIN_M) / s;
    const d = SLAB_DEPTH_M / s;
    const mat = new MeshStandardMaterial({
      color: T.hull,
      roughness: 0.55,
      metalness: 0.3,
      emissive: EDGE_OFF.clone(),
      emissiveIntensity: 1,
    });
    const slab = new Mesh(new BoxGeometry(w, h, d), mat);
    slab.name = 'panel-slab';
    slab.position.z = -d / 2 - 0.02;
    (slab as unknown as { pointerEvents: string }).pointerEvents = 'none';
    root.add(slab);
    return mat;
  }

  private paintEdge(v: PanelView): void {
    const sel = v.highlightUntil > 0;
    v.slab.emissive.copy(v.asking ? EDGE_ASK : sel ? EDGE_SEL : EDGE_OFF);
    v.slab.emissiveIntensity = v.asking ? 0.55 : 0.45;
    v.el['panel-root']?.setProperties({
      borderColor: v.asking ? mix(T.amber, 0.55) : sel ? mix(T.ion, 0.55) : T.seam,
    } as never);
  }

  /** Place every deck element relative to the current head pose (design s2 "recentre"). */
  recentre(): void {
    const cam = this.world.camera;
    cam.getWorldPosition(this.eye);
    cam.getWorldDirection(this.fwd);
    this.fwd.y = 0;
    if (this.fwd.lengthSq() < 1e-6) this.fwd.set(0, 0, -1);
    this.fwd.normalize();
    placeRoom(this.eye, this.fwd);
    const right = this.tmp.set(-this.fwd.z, 0, this.fwd.x); // fwd x up
    for (const [node, [x, y, z]] of Object.entries(LAYOUT)) {
      if (node.startsWith('panel-') && deck.runtime?.portfolio.stations.size) continue;
      const obj = this.objectFor(node);
      if (!obj) continue;
      obj.position.set(
        this.eye.x + right.x * x + this.fwd.x * z,
        this.eye.y + y,
        this.eye.z + right.z * x + this.fwd.z * z,
      );
      obj.lookAt(this.eye); // panels face +Z: billboard to the eye at spawn, then world-fixed
      const view = this.views.get(node.replace('panel-', ''));
      if (view) {
        view.base.copy(obj.position);
        view.raised = false;
      }
    }
    deck.info(
      `recentred at eye (${this.eye.x.toFixed(2)}, ${this.eye.y.toFixed(2)}, ${this.eye.z.toFixed(2)})`,
    );
    if (deck.runtime) for (const a of deck.runtime.agents.values()) this.render(a);
  }

  private objectFor(node: string): Object3D | null {
    if (node.startsWith('panel-')) return this.views.get(node.slice(6))?.root ?? null;
    return this.world.getSceneEntity(node)?.object3D ?? null;
  }

  private render(a: AgentState): void {
    if (deck.runtime?.portfolio.stations.size) return;
    const v = this.views.get(a.id);
    if (!v) return;
    const set = (id: string, props: Record<string, unknown>) =>
      v.el[id]?.setProperties(props as never);
    const chip = CHIP[a.status];
    set('role-label', { text: ascii(a.name) });
    set('status-chip', { backgroundColor: chip.bg, borderColor: chip.border });
    set('status-text', { text: a.held ? 'HELD' : chip.text, color: chip.fg });
    const done = a.steps.filter((r) => r.status === 'COMPLETED').length;
    const n = a.steps.length;
    set('strip-label', {
      text: n ? `STEP ${Math.min(done + 1, n)}/${n}` : 'NO STEPS YET',
      color: chip.fg,
    });
    set('mode-label', { text: modeTag(deck.modeLine()) });
    set('current-label', { text: ascii(clip(a.currentStep || a.task || '-', 40)) });
    // The latest STEP_ROWS steps (the live end of the run).
    const first = Math.max(0, n - STEP_ROWS);
    for (let i = 0; i < STEP_ROWS; i++) {
      const row = a.steps[first + i];
      set(`step-${i}`, {
        text: row ? ascii(`${ROW_MARK[row.status]}${clip(row.title, 38)}`) : '',
        color: row && row.status !== 'COMPLETED' ? STATUS[row.status].c : T.ink2,
      });
    }
    let note = a.note || (a.task ? `Task: ${a.task}` : '');
    const q = a.question;
    if (q) {
      const left = Math.max(
        0,
        Math.ceil(deck.autoAnswerS - (performance.now() - q.askedAtReal) / 1000),
      );
      note = `${q.title} (auto in ${left} s)`;
    }
    set('note-label', { text: ascii(clip(note, 84)), color: q ? T.ink : T.ink2 });
    if (v.enabled !== !!q) {
      v.enabled = !!q;
      for (const k of ['approve', 'stop'] as const) {
        set(`${k}-button`, { borderColor: q ? BTN[k].border : T.seam });
        set(`${k}-label`, { color: q ? BTN[k].fg : T.ink3 });
        set(`${k}-icon`, { color: q ? BTN[k].fg : T.ink3 });
      }
      set('gesture-hint', { display: q ? 'flex' : 'none' });
      set('hold-track', { display: q ? 'flex' : 'none' });
      v.asking = !!q;
      this.paintEdge(v);
    }
    // Design s5: the asking panel rises 3 cm.
    if (!!q !== v.raised && v.base.lengthSq() > 0) {
      v.raised = !!q;
      v.root.position.copy(v.base);
      if (q) v.root.position.y += RISE_M;
    }
  }

  private renderAssign(): void {
    for (const v of this.views.values()) {
      v.el['assign-button']?.setProperties({
        display: deck.selectedCard ? 'flex' : 'none',
        borderColor: BTN.assign.border,
      } as never);
    }
  }
}
