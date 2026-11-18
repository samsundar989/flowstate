/**
 * Graph system: places the GraphView 1.0-1.4 m out between the panels (design s2), feeds it
 * runtime events, handles ray + pinch selection on nodes and the node detail card.
 *
 * Test hooks (emulator scripts; keyboard on the page):
 *   g  log the world position of every drawn node ("graph node <id> at x y z")
 *   i  log renderer.info draw calls / triangles of the last frame
 *   h  hide / show the graph (draw calls with and without it)
 *   n  next fixture (same as the tray's scenario button)
 * and window.__overhand.graph = { view, select(id) }.
 */
import {
  createSystem,
  Object3D,
  RayInteractable,
  UIKit,
  UIKitMLAsset,
  Vector3,
  VisibilityState,
} from '@iwsdk/core';
import { deck } from '../deck.js';
import { CHIP } from '../panels.js';
import { CARD_MIN_Y, GRAPH_LABEL_DEG, GRAPH_LIFT_M, mix, STATUS, T } from '../theme.js';
import { GraphView, type NodeRec } from './view.js';
import { DEFAULT_LAYOUT } from './layout.js';
import type { AgentEvent } from '../replay.js';

type El = NonNullable<ReturnType<UIKitMLAsset['getElementById']>>;
const CARD_FIELDS = [
  'card-agent',
  'card-chip',
  'card-status',
  'card-title',
  'card-tool',
  'card-duration',
  'card-detail',
  'jump-button',
  'close-button',
] as const;
const HIDDEN_Y = -50;
const ROLE: Record<string, string> = {
  researcher: 'Researcher',
  builder: 'Builder',
  reviewer: 'Reviewer',
};
const clip = (s: string, n: number) => (s.length > n ? `${s.slice(0, n - 3)}...` : s);
const ascii = (s: string) => s.replace(/[^\x20-\x7E]/gu, '-');

/** A mono micro-label that floats above a node (hover, selection), sized by angle. */
interface NodeLabel {
  root: UIKit.Container;
  text: UIKit.Text;
  node: string | null;
  key: string;
}
const FONT = (w: string) => `${import.meta.env.BASE_URL}fonts/mono-${w}.json`;

export class GraphSystem extends createSystem({}) {
  private labels: NodeLabel[] = [];
  private lp = new Vector3();
  private history: AgentEvent[] = [];
  private promoted: string | null = null;
  readonly view = new GraphView();
  private eye = new Vector3();
  private fwd = new Vector3();
  private tmp = new Vector3();
  private recentreIn = -1;
  private card: Object3D | null = null;
  private el: Partial<Record<(typeof CARD_FIELDS)[number], El>> = {};
  private shown = new Map<string, string>();
  private cardFor: string | null = null;
  private nextCardRefresh = 0;

  init(): void {
    const v = this.view;
    v.log = (m) => deck.info(m);
    const entity = this.world.createTransformEntity(v.root);
    entity.addComponent(RayInteractable);

    // Ray + pinch select: pointerdown then pointerup on the collider.
    let armed: number | undefined;
    type PE = { instanceId?: number };
    const down = (e: unknown) => (armed = (e as PE).instanceId);
    // The pinch itself nudges a hand ray, so the node under the ray at pinch-down wins.
    const up = () => {
      if (armed !== undefined) v.pick(armed);
      armed = undefined;
    };
    const col = v.collider as unknown as {
      addEventListener(t: string, f: (e: unknown) => void): void;
      removeEventListener(t: string, f: (e: unknown) => void): void;
    };
    const move = (e: unknown) => v.hover((e as PE).instanceId);
    const leave = () => v.hover(undefined);
    col.addEventListener('pointerdown', down);
    col.addEventListener('pointerup', up);
    col.addEventListener('pointermove', move);
    col.addEventListener('pointerleave', leave);
    this.cleanupFuncs.push(() => {
      col.removeEventListener('pointerdown', down);
      col.removeEventListener('pointerup', up);
      col.removeEventListener('pointermove', move);
      col.removeEventListener('pointerleave', leave);
    });

    deck.onEvent((e) => {
      this.history.push(e);
      if (!e.project || e.project === this.promoted) v.onEvent(e, performance.now());
    });
    deck.onReset(() => { this.history = []; this.promoted = null; v.root.visible = true; v.reset(); });

    // Card.
    const doc = this.world.getSceneObject<UIKitMLAsset>('node-card');
    this.card = this.world.getSceneEntity('node-card')?.object3D ?? null;
    if (!doc || !this.card) {
      console.error('[overhand] node-card missing from the scene');
    } else {
      for (const id of CARD_FIELDS) {
        const e = doc.getElementById(id);
        if (e) this.el[id] = e;
      }
      // Card buttons: a hand press often lands on the card body first and slides onto the
      // button (pointerdown on the card, pointerup on the button), so arm on a pointerdown
      // anywhere on the card and fire on a pointerup over the button within 1.5 s.
      let armedAt = 0;
      const cardObj = this.card as unknown as {
        addEventListener(t: string, f: () => void): void;
        removeEventListener(t: string, f: () => void): void;
      };
      const arm = () => (armedAt = performance.now());
      cardObj.addEventListener('pointerdown', arm);
      this.cleanupFuncs.push(() => cardObj.removeEventListener('pointerdown', arm));
      const onRelease = (id: 'jump-button' | 'close-button', fn: () => void) => {
        const el = this.el[id];
        if (!el) return;
        const up = () => {
          if (!armedAt || performance.now() - armedAt > 1500) return;
          armedAt = 0;
          fn();
        };
        el.addEventListener('pointerup', up);
        this.cleanupFuncs.push(() => el.removeEventListener('pointerup', up));
      };
      onRelease('jump-button', () => {
        const rec = this.cardFor ? v.nodes.get(this.cardFor) : undefined;
        if (rec) deck.highlight(rec.lane);
      });
      onRelease('close-button', () => {
        deck.info('card closed');
        v.selected = null;
      });
      this.hideCard();
    }

    this.cleanupFuncs.push(
      this.world.visibilityState.subscribe((s) => {
        if (s !== VisibilityState.NonImmersive) this.recentreIn = 30;
      }),
    );
    const onKey = (e: KeyboardEvent) => this.onKey(e.key);
    window.addEventListener('keydown', onKey);
    this.cleanupFuncs.push(() => window.removeEventListener('keydown', onKey));
    this.labels = [this.makeLabel(), this.makeLabel()];
    (deck as unknown as { graph: unknown }).graph = {
      view: v,
      select: (id: string) => v.selectDrawn(id),
    };
    this.recentre();
  }

  update(delta: number): void {
    const now = performance.now();
    if (this.recentreIn > 0 && --this.recentreIn === 0) this.recentre();
    const p = deck.runtime?.portfolio;
    if (p?.stations.size) {
      if (p.promoted !== this.promoted) {
        this.promoted = p.promoted;
        this.view.reset();
        const lanes = [...(p.stations.get(this.promoted ?? '')?.agents ?? [])];
        this.view.options = { ...DEFAULT_LAYOUT, lanes, laneX: lanes.map((_, i) => (i - (lanes.length - 1) / 2) * 0.20), y0: 0.24 };
        for (const e of this.history) if (e.project === this.promoted) this.view.onEvent(e, now);
      }
      this.view.root.visible = !!this.promoted && !p.queue.length;
      if (!this.view.root.visible) this.view.selected = null;
    } else this.view.options = DEFAULT_LAYOUT;
    this.view.update(now);
    const hov = this.view.hovered !== this.view.selected ? this.view.hovered : null;
    this.placeLabel(this.labels[0], hov);
    this.placeLabel(this.labels[1], this.view.selected);
    for (const l of this.labels) if (l.node) l.root.update(delta * 1000);
    const sel = this.view.selected;
    if (sel !== this.cardFor) {
      this.cardFor = sel;
      if (sel) this.placeCard(sel);
      else this.hideCard();
    }
    if (sel && now >= this.nextCardRefresh) {
      this.nextCardRefresh = now + 250;
      this.fillCard(sel, now);
    }
  }

  private makeLabel(): NodeLabel {
    const text = new UIKit.Text({
      text: '',
      fontFamily: 'mono',
      fontWeight: 600,
      fontSize: 20,
      letterSpacing: 1,
      color: T.ink,
    });
    const root = new UIKit.Container({
      pixelSize: 0.001, // 1 unit = 1 mm
      fontFamilies: { mono: { normal: FONT('regular'), 'semi-bold': FONT('semibold') } },
      flexDirection: 'row',
      paddingX: 8,
      paddingY: 4,
      borderWidth: 1,
      borderRadius: 2,
      borderColor: mix(T.ion, 0.5),
      backgroundColor: T.deck,
      anchorY: 'bottom',
    });
    root.add(text);
    root.visible = false;
    (root as unknown as { pointerEvents: string }).pointerEvents = 'none';
    this.view.root.add(root);
    return { root, text, node: null, key: '' };
  }

  /** Above the node, facing the eye, glyphs GRAPH_LABEL_DEG tall at its distance. */
  private placeLabel(l: NodeLabel, id: string | null): void {
    const p = id ? this.view.localPos(id) : undefined;
    const rec = id ? this.view.nodes.get(id) : undefined;
    if (!p || !id) {
      if (l.node) {
        l.node = null;
        l.root.visible = false;
      }
      return;
    }
    l.root.position.set(p.x, p.y + 0.045, p.z + 0.07); // above and in front of the node
    l.root.renderOrder = 10;
    l.root.visible = true;
    this.lp.copy(l.root.position);
    this.view.root.localToWorld(this.lp);
    const d = this.lp.distanceTo(this.eye);
    l.root.lookAt(this.eye);
    const st = rec ? STATUS[rec.status] : null;
    const title = rec ? rec.title : 'grouped steps';
    const size = Math.round(2 * d * Math.tan((GRAPH_LABEL_DEG * Math.PI) / 360) * 1000);
    const key = `${id}|${rec?.status}|${size}`;
    l.node = id;
    if (key === l.key) return;
    l.key = key;
    l.text.setProperties({
      text: ascii(clip(`${st ? `${st.text}  ` : ''}${title}`.toUpperCase(), 30)),
      fontSize: size,
      color: st ? st.c : T.ink2,
    } as never);
    l.root.setProperties({ borderColor: st ? mix(st.c, 0.5) : T.seamHi } as never);
  }

  /** Same head-relative frame as the panels (design s2): local -z = forward. */
  private recentre(): void {
    const cam = this.world.camera;
    cam.getWorldPosition(this.eye);
    cam.getWorldDirection(this.fwd);
    this.fwd.y = 0;
    if (this.fwd.lengthSq() < 1e-6) this.fwd.set(0, 0, -1);
    this.fwd.normalize();
    const root = this.view.root;
    root.position.copy(this.eye);
    root.position.y += GRAPH_LIFT_M;
    root.rotation.set(0, Math.atan2(-this.fwd.x, -this.fwd.z), 0);
    root.updateMatrixWorld(true);
    if (this.cardFor) this.placeCard(this.cardFor);
  }

  private placeCard(id: string): void {
    const p = this.view.localPos(id);
    if (!p || !this.card) return;
    // Beside the node on the near face (z = 1.00); outer lanes put the card inward.
    const side = p.x > 0.1 ? -1 : 1;
    // card 0.44 m wide: clear of the node; never low enough for the panels to hide it.
    this.tmp.set(p.x + side * 0.29, Math.max(p.y, CARD_MIN_Y), -1.0);
    this.view.root.localToWorld(this.tmp);
    this.card.position.copy(this.tmp);
    this.card.visible = true;
    this.card.lookAt(this.eye);
    this.fillCard(id, performance.now());
    const rec = this.view.nodes.get(id);
    deck.info(`card open ${id} (${rec?.status ?? '?'}) ${ascii(clip(rec?.title ?? '', 50))}`);
  }

  private hideCard(): void {
    if (!this.card) return;
    this.card.position.set(0, HIDDEN_Y, 0); // out of every ray's way
    this.card.visible = false;
  }

  private fillCard(id: string, now: number): void {
    const rec: NodeRec | undefined = this.view.nodes.get(id);
    if (!rec) return;
    const chip = CHIP[rec.status];
    const dur =
      rec.tEndMs != null
        ? `Took ${((rec.tEndMs - rec.tStartMs) / 1000).toFixed(1)} s (recorded)`
        : `Running ${Math.round(((now - rec.bornAt) * deck.speed) / 1000)} s`;
    const tool = rec.tool
      ? `Tool: ${rec.tool}${rec.toolCount > 1 ? ` (${rec.toolCount} calls)` : ''}`
      : 'Tool: -';
    this.set('card-agent', {
      text: ascii(clip(`${(ROLE[rec.lane] ?? rec.lane).toUpperCase()} / ${rec.id}`, 22)),
    });
    this.set('card-chip', { backgroundColor: chip.bg, borderColor: chip.border });
    this.set('card-status', { text: chip.text, color: chip.fg });
    this.set('card-title', { text: ascii(clip(rec.title, 56)) });
    this.set('card-tool', { text: ascii(clip(tool, 33)) });
    this.set('card-duration', { text: dur });
    this.set('card-detail', { text: ascii(clip(rec.detail || '-', 60)) });
  }

  /** setProperties only when the value changed (rewrites mid-press drop the press). */
  private set(id: (typeof CARD_FIELDS)[number], props: Record<string, unknown>): void {
    const key = JSON.stringify(props);
    if (this.shown.get(id) === key) return;
    this.shown.set(id, key);
    this.el[id]?.setProperties(props as never);
  }

  private onKey(key: string): void {
    const v = this.view;
    if (key === 'r') { this.recentre();
    } else if (key === 'g') {
      const L = v.layout;
      deck.info(`graph dump ${L?.nodes.length ?? 0} drawn, layers ${L ? L.maxLayer + 1 : 0}`);
      for (const n of L?.nodes ?? []) {
        if (n.layer < (L?.firstLayer ?? 0)) continue;
        const p = v.localPos(n.id);
        if (!p) continue;
        this.tmp.copy(p);
        v.root.localToWorld(this.tmp);
        const st = v.nodes.get(n.id)?.status ?? 'AGG';
        deck.info(
          `graph node ${n.id} ${st} at ${this.tmp.x.toFixed(3)} ${this.tmp.y.toFixed(3)} ${this.tmp.z.toFixed(3)}`,
        );
      }
    } else if (key === 'i') {
      const info = this.world.renderer.info.render;
      deck.info(
        `render calls=${info.calls} triangles=${info.triangles} graph=${v.root.visible ? 'on' : 'off'} nodes=${v.nodes.size} drawn=${v.layout?.nodes.length ?? 0}`,
      );
    } else if (key === 'n') {
      void deck.nextFixture();
    } else if (key === 'h') {
      v.root.visible = !v.root.visible;
      deck.info(`graph ${v.root.visible ? 'shown' : 'hidden'}`);
    }
  }
}
