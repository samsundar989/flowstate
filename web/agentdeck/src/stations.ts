import {
  createSystem, UIKitMLAsset, RayInteractable, PokeInteractable, Vector3,
  BufferGeometry, Line, LineBasicMaterial, Float32BufferAttribute, VisibilityState,
  Mesh, MeshBasicMaterial, PlaneGeometry,
  type Entity,
  Object3D,
} from '@iwsdk/core';
import { deck, type PracticeView } from './deck.js';
import { cardCentreY, centreStation, decisionBackdrop, gateLabel, guidedStage, projectPositionLabel, paginateText, recommendedOption, stationRows, stationState, returnLabel, stationHeightUnits, stationInspecting, stationPosition, steppedStation, thumbsUpOption, wrappedLines, type Decision, type TextBlock, type TextPage, type Portfolio, type Station } from './portfolio.js';
import { introCard, tutorialCard, type CardCopy } from './standup.js';
import { hex, mix, NEAR_M, PROMOTED_SCALE, STATION_ARC_M, STATION_SCALE, STATION_W_UNITS, STATUS, T } from './theme.js';
import { receiptEvidence } from './practice.js';
import { onPress } from './tray.js';
import { Sounds } from './sound.js';
import { cueFor } from './cues.js';

/** The open-question count, worded the same on the question card and on its station. */
const openLabel = (n: number): string => n ? `${n} OPEN DECISION${n === 1 ? '' : 'S'}` : 'NO OPEN DECISIONS';

type Surface = { doc: UIKitMLAsset; entity: Entity; off: (() => void)[]; cache: Map<string, string>; slot?: { row: number; x: number }; optionId?: string };
const ascii = (s: string) => s.replace(/[^\x20-\x7E\n]/gu, '-');
/** Practice card: the column sits higher so a fourth row (Confirm / Skip) clears the tray. */
const PRACTICE_CARD_Y = 0.08;
/** First option row. With four rows the action row's bottom edge stays near 30 deg below level. */
const PRACTICE_OPTION_Y = 0.002;
/** The near project column keeps its original rows; only the decision column moved up. */
const NAV_ORIGIN_Y = -0.045;
/** Tallest decision card; with the column below it, its top edge stays near 30 deg above level at a level gaze. */
const CARD_MAX_UNITS = 720;
const PAGE_LINE_UNITS = 43 + 16;
const FIXED_CARD_UNITS = 36 * 2 + 4 * 2 + (16 + 16); // padding, border, hold bar and its gap
const HEADER_LINE_UNITS = 41;
/** Characters per line at each field's font, conservative against measured widths so a page never overflows. */
const WRAP = { question: 40, recommend: 48, default: 52, evidence: 54, hint: 55, header: 50 };
const OPTION_PITCH_M = 0.062; // 50 mm button plus 12 mm clear gap
const OPTION_HALF_M = 0.025;
/** 1 uikit unit at the near surfaces' 0.05 scale (pixel-size 0.005). */
const NEAR_UNIT_M = 0.00025;
/** Half-width of the 1280-unit decision column, and of a card or option on it. */
const COLUMN_HALF_M = 0.16;
/** Behind every near surface (their tilted edges reach about 0.456 m), far in front of the arc. */
const BACKDROP_Z_M = 0.47;
/** Half-width action buttons: 620 units = 155 mm; 12 mm between them. */
const ACTION_W_UNITS = 620;
const ACTION_X_M = 0.0835;
/** The one project shown beside a practice card, clear of the card's +-20 deg edge. */
const CONTEXT_STATION_DEG = -31;
/** Near-field Bring Here, right of the 0.32 m card: the arc at 1.10 m is out of a seated reach. */
const BRING_X_M = 0.25;
const BRING_ROW = -1;
/** Near project column, in the Bring slot and below it: the arc's far buttons cannot be poked seated. */
const NAV_ROWS = { label: BRING_ROW, previous: 0, next: 1, back: 2 };
/** 5 mm inside Bring: the lowest row (Back) would otherwise sit farther from the seat than any existing near control. */
const NAV_X_M = 0.245;

/** N manifest-backed stations. Near focus is shared by the promoted station and inbox. */
export class StationSystem extends createSystem({}) {
  private stations = new Map<string, Surface>();
  private options: Surface[] = [];
  private card: Surface | null = null;
  /** Fixed near column while a station holds the near slot: label, previous, next, back. */
  private nav: Surface[] = [];
  private eye = new Vector3();
  private forward = new Vector3();
  private right = new Vector3();
  private ghostForward = new Vector3(0, 0, -1);
  private sounds!: Sounds;
  private generation = 0;
  private current = '';
  private nextRefresh = 0;
  private summaryUntil = 0;
  private hadQueue = false;
  private recentreIn = 0;
  private busy = false;
  private holdTenths = 0;
  private ghosts = new Map<string, Object3D>();
  /** The card key a station was brought over; see stationInspecting. */
  private inspectKey = '';
  private nearId = '';
  private pages: TextPage[] = [];
  private page = 0;
  private choiceText: string | null = null;
  private pager: Surface[] = [];
  /** Opaque plate behind the open decision: the arc cannot show through its gaps or be pressed unseen. */
  private backdrop!: Mesh;

  init(): void {
    this.backdrop = new Mesh(new PlaneGeometry(1, 1), new MeshBasicMaterial({ color: hex(T.void) }));
    this.backdrop.name = 'Decision backdrop';
    this.backdrop.visible = false;
    this.world.createTransformEntity(this.backdrop).addComponent(RayInteractable);
    for (const [stage, asset] of [['station', 'ghost-poke'], ['scrub', 'ghost-pinch'], ['thumbs', 'ghost-thumb']]) {
      void this.world.assets.instantiate<Object3D>(asset).then(ghost => {
        ghost.name = `Guide hand ${stage}`;
        ghost.visible = false;
        this.world.createTransformEntity(ghost);
        this.ghosts.set(stage, ghost);
      });
    }
    this.sounds = new Sounds(this.world.camera, id => {
      const p = deck.runtime?.portfolio;
      const station = p?.stations.get(id) ?? [...(p?.stations.values() ?? [])].find(s => s.agents.has(id));
      return station ? this.stations.get(station.id)?.doc ?? null : this.card?.doc ?? null;
    });
    deck.onReset(() => { void this.rebuild(); });
    deck.onEvent(e => {
      if (!e.project && !e.decision_id) return;
      const cue = cueFor(e);
      if (cue) this.sounds.play(cue, e.project ?? e.agent_id);
    });
    this.cleanupFuncs.push(this.world.visibilityState.subscribe(v => {
      if (v !== VisibilityState.NonImmersive) {
        this.recentreIn = 30;
        this.sounds.resume();
      }
    }));
    const key = (e: KeyboardEvent) => {
      if (e.key === 'r') this.recentre();
      // Debug navigation uses the same model operations as hand controls.
      if (e.key === 'p') {
        const p = deck.runtime?.portfolio;
        const id = p && [...p.stations.keys()][0];
        if (id) this.promote(id);
      }
    };
    window.addEventListener('keydown', key);
    this.cleanupFuncs.push(() => window.removeEventListener('keydown', key));
    this.recentre();
    if (deck.runtime) void this.rebuild();
  }

  private async surface(asset: string, name: string): Promise<Surface> {
    const doc = await this.world.assets.instantiate<UIKitMLAsset>(asset);
    doc.name = name;
    doc.scale.setScalar(STATION_SCALE);
    const entity = this.world.createTransformEntity(doc);
    entity.addComponent(RayInteractable);
    entity.addComponent(PokeInteractable);
    return { doc, entity, off: [], cache: new Map() };
  }
  private dispose(s: Surface): void {
    for (const off of s.off) off();
    s.entity.dispose();
  }
  private set(s: Surface, id: string, text: string, extra = {}): void {
    const props = { text: ascii(text), ...extra };
    const value = JSON.stringify(props);
    if (s.cache.get(id) === value) return;
    s.cache.set(id, value);
    s.doc.getElementById(id)?.setProperties(props as never);
  }

  private async rebuild(): Promise<void> {
    const gen = ++this.generation;
    for (const s of this.stations.values()) this.dispose(s);
    this.stations.clear();
    this.clearOptions();
    for (const s of this.nav) this.dispose(s);
    this.nav = [];
    if (this.card) this.dispose(this.card);
    this.card = null;
    this.current = '';
    this.hadQueue = false;
    this.summaryUntil = 0;
    const p = deck.runtime?.portfolio;
    if (!p?.stations.size) return;
    for (const station of p.stations.values()) {
      const s = await this.surface('project-station', `Station ${station.name}`);
      if (gen !== this.generation) { this.dispose(s); return; }
      s.off.push(onPress(s.doc.getElementById('promote-button')!, () => this.promote(station.id)));
      this.stations.set(station.id, s);
      this.renderStation(station, s);
    }
    const card = await this.surface('decision-card', 'Decision Inbox');
    if (gen !== this.generation) { this.dispose(card); return; }
    this.card = card;
    const at = (row: number) => ({ row, x: NAV_X_M });
    const nav = [
      await this.navLabel(at(NAV_ROWS.label)),
      await this.action('Project previous', 'PREVIOUS PROJECT', at(NAV_ROWS.previous), () => this.step(-1)),
      await this.action('Project next', 'NEXT PROJECT', at(NAV_ROWS.next), () => this.step(1)),
      await this.action('Project back', 'BACK', at(NAV_ROWS.back), () => { if (this.nearId) this.promote(this.nearId); }),
    ];
    if (gen !== this.generation) { nav.forEach(s => this.dispose(s)); return; }
    this.nav = nav;
    // Keep the frame the user last recentred to. A seek is released while the user looks down at
    // the tray; re-centring here yawed the whole layout toward that glance (and, in the emulator,
    // left option presses stopping short of the yawed panels: #113 "pokes stopped").
    this.place();
  }

  private promote(id: string): void {
    const p = deck.runtime?.portfolio;
    if (!p) return;
    // A station left promoted before this card opened is on the arc, so this press brings it here.
    const stale = p.promoted === id && this.nearId !== id;
    if (!stale) p.promote(id);
    this.inspectKey = p.promoted ? this.current : '';
    deck.info(`station ${id} ${p.promoted ? 'promoted' : 'demoted'}`);
    if (deck.standup?.stage === 'station') deck.advanceTutorial();
    this.place();
  }
  /** Moves the near slot along the arc; the card it stands over keeps its key, choice and receipt. */
  private step(delta: 1 | -1): void {
    const p = deck.runtime?.portfolio;
    if (!p || !this.nearId) return;
    const next = steppedStation([...p.stations.keys()], this.nearId, delta);
    if (!next || next === this.nearId) return;
    p.promote(next);
    deck.info(`station ${next} selected`);
    this.place();
  }
  private async navLabel(slot: { row: number; x: number }): Promise<Surface> {
    const s = await this.surface('decision-option', 'Project position');
    s.slot = slot;
    this.style(s, 'option-button', { width: ACTION_W_UNITS, borderColor: T.seam });
    return s;
  }
  private clearOptions(): void {
    for (const s of this.options) this.dispose(s);
    this.options.length = 0;
    this.pager = [];
  }
  private async renderDecision(d: Decision | undefined): Promise<void> {
    const card = this.card;
    const rt = deck.runtime;
    if (!card || !rt) return;
    const gen = this.generation;
    this.busy = true;
    this.clearOptions();
    this.endPaging(card);
    const p = rt.portfolio;
    if (d) {
      const thumb = thumbsUpOption(d);
      const recommendation = thumb?.via === 'recommendation' ? d.options?.find(o => o.id === thumb.id) : undefined;
      const fallback = thumb?.via === 'default' ? d.options?.find(o => o.id === thumb.id) : undefined;
      const practice = d.decision_id === 'tutorial' ? tutorialCard('thumbs', '', '', '') : null;
      if (practice) this.copy(card, practice);
      else {
        this.set(card, 'decision-header', `${d.blocking ? 'BLOCKING' : 'DECISION'} / ${p.queue.length} OPEN / ${(p.stations.get(d.project ?? '')?.name ?? 'Portfolio').toUpperCase()}`);
        this.set(card, 'decision-question', d.title ?? 'Choose an option');
        // One option needs no recommendation line (#699).
        const single = (d.options?.length ?? 0) < 2;
        this.set(card, 'decision-recommend', recommendation ? `Recommended: ${recommendation.label}` :
          fallback ? `Thumbs-up takes the default: ${fallback.label}` : 'Poke an option.', { display: single ? 'none' : 'flex' });
        this.set(card, 'decision-default', '-', { display: 'flex' });
        this.set(card, 'decision-evidence', d.evidence?.split(/\r?\n/u).find(Boolean) ?? 'Evidence not supplied', { display: 'flex' });
        // A malformed recommendation is said in one mono line, never hidden.
        const malformed = `"${(d.recommendation ?? 'none').toUpperCase()}" MATCHES NO OPTION`;
        this.set(card, 'decision-hint', recommendation ? 'THUMBS-UP 0.5 S ACCEPTS / THUMBS-DOWN SNOOZES / OR POKE' :
          fallback ? `${malformed} / UP = DEFAULT / DOWN SNOOZES` : `${malformed} / POKE ONE / DOWN SNOOZES`, { display: 'flex' });
      }
      this.style(card, 'decision-root', { borderColor: mix(T.amber, 0.55) });
      for (const opt of d.options ?? []) {
        const s = await this.surface('decision-option', `Decision ${d.decision_id} option ${opt.id}`);
        if (gen !== this.generation) { this.dispose(s); this.busy = false; return; }
        const rec = opt.id === thumb?.id;
        this.set(s, 'option-label', opt.label, { color: rec ? T.ion : T.ink });
        this.style(s, 'option-button', { borderColor: rec ? T.ion : T.seamHi, backgroundColor: rec ? STATUS.WORKING.dim : T.deck });
        s.off.push(onPress(s.doc.getElementById('option-button')!, () => {
          if (rt.portfolio.queue[0]?.decision_id !== d.decision_id) return;
          deck.info(`poke decision ${d.decision_id} -> ${opt.id}`);
          rt.answerDecision(d.decision_id!, opt.id);
        }));
        this.options.push(s);
      }
    } else {
      this.style(card, 'decision-root', { borderColor: mix(T.green, 0.55) });
      this.set(card, 'decision-header', 'INBOX ZERO');
      this.set(card, 'decision-question', `${p.answered} answered / ${p.snoozed} snoozed`);
      const running = [...p.stations.values()].filter(s => [...s.rows.values()].some(row => row.status === 'WORKING'));
      this.set(card, 'decision-recommend', running.length ? `Running: ${running.map(s => s.name).join(', ')}` : 'All caught up');
      this.set(card, 'decision-default', deck.standup?.minutes == null ? 'NO OPEN DECISIONS' : `STAND-UP ${deck.standup.minutes.toFixed(1)} MIN`, { display: 'flex' });
      this.set(card, 'decision-evidence', 'Your projects keep their places around you.', { display: 'flex' });
      this.set(card, 'decision-hint', 'PINCH A STATION TO INSPECT IT', { display: 'flex' });
      const centre = [...p.stations.values()][centreStation(p.stations.size)];
      if (deck.standup?.stage === 'complete' && centre && this.bringable(centre)) {
        const s = await this.bringAction(centre);
        if (gen !== this.generation) { this.dispose(s); this.busy = false; return; }
        this.options.push(s);
      }
    }
    this.busy = false;
    this.place();
  }

  private async action(name: string, label: string, slot: { row: number; x: number }, fn: () => void, width = ACTION_W_UNITS): Promise<Surface> {
    const s = await this.surface('decision-option', name);
    s.slot = slot;
    this.style(s, 'option-button', { width, justifyContent: 'center' });
    this.set(s, 'option-label', label, { color: T.ink });
    s.off.push(onPress(s.doc.getElementById('option-button')!, fn));
    return s;
  }

  /** BRING HERE only for a station with an open decision that is not already the near one (#699). */
  private bringable(station: Station): boolean {
    return station.id !== this.nearId && !!deck.runtime?.portfolio.queue.some(d => d.project === station.id);
  }

  private bringAction(station: Station): Promise<Surface> {
    return this.action(`Bring ${station.name}`, `BRING HERE / ${station.name.toUpperCase()}`, { row: BRING_ROW, x: BRING_X_M }, () => this.promote(station.id));
  }

  /** Purpose, one decision or one receipt; options and actions are rebuilt per view. */
  private async renderPractice(view: PracticeView): Promise<void> {
    const card = this.card;
    const rt = deck.runtime;
    if (!card || !rt) return;
    const gen = this.generation;
    this.busy = true;
    this.clearOptions();
    const add = (s: Surface): boolean => {
      if (gen !== this.generation) { this.dispose(s); this.busy = false; return false; }
      this.options.push(s);
      return true;
    };
    if (view.kind === 'intro') {
      this.copy(card, introCard());
      this.style(card, 'decision-root', { borderColor: mix(T.ion, 0.55) });
      if (!add(await this.action('Practice begin', 'BEGIN', { row: 0, x: 0 }, () => deck.practiceBegin(), 1280))) return;
    } else if (view.kind === 'receipt') {
      const r = view.r;
      this.pagedCopy(card, {
        header: `SAVED ON THIS DEVICE / ${r.project_name.toUpperCase()}`,
        question: r.question,
        recommend: `You chose: ${r.option_label}`,
        default: `RECEIPT ${r.receipt_id}`,
        evidence: receiptEvidence(r.decision_id),
        hint: 'NEXT SHOWS THE NEXT RECORDED QUESTION',
      });
      this.style(card, 'decision-root', { borderColor: mix(T.green, 0.55) });
      // Right of where Confirm was: a repeated Confirm press lands on empty air.
      if (!add(await this.action(`Receipt ${r.decision_id} next`, 'NEXT QUESTION', { row: 0, x: ACTION_X_M }, () => deck.practiceNext(performance.now())))) return;
      const project = rt.portfolio.stations.get(r.project);
      if (project && this.bringable(project) && !add(await this.bringAction(project))) return;
      if (!await this.addPager(add)) return;
    } else {
      const d = view.d;
      const recommended = d.options?.find(o => o.id === recommendedOption(d));
      const longest = (d.options ?? []).reduce((a, o) => a.length >= o.label.length ? a : o.label, '');
      this.pagedCopy(card, {
        header: `RECORDED PRACTICE / ${(rt.portfolio.stations.get(d.project ?? '')?.name ?? 'Portfolio').toUpperCase()} / ${openLabel(rt.portfolio.queue.filter(q => q.project === d.project).length)}`,
        question: d.title ?? 'Choose an option',
        recommend: recommended ? `Agent recommends: ${recommended.label}` : `Recorded recommendation "${d.recommendation ?? 'none'}" matches no option`,
        default: 'CHOOSE ONE BELOW',
        evidence: d.evidence?.trim() ? d.evidence.replace(/\r\n?/gu, '\n') : 'Evidence not supplied',
        hint: 'CONFIRM KEEPS YOUR CHOICE / SKIP FOR NOW ASKS LATER',
      }, `YOUR CHOICE: ${longest.toUpperCase()} / 99 SKIPPED`);
      this.style(card, 'decision-root', { borderColor: mix(T.amber, 0.55) });
      const options = d.options ?? [];
      for (const [row, opt] of options.entries()) {
        const s = await this.surface('decision-option', `Decision ${d.decision_id} option ${opt.id}`);
        s.slot = { row, x: 0 };
        s.optionId = opt.id;
        s.cache.set('label', opt.label);
        s.off.push(onPress(s.doc.getElementById('option-button')!, () => deck.practiceSelect(d, opt.id, performance.now())));
        if (!add(s)) return;
      }
      const row = options.length;
      if (!add(await this.action(`Decision ${d.decision_id} confirm`, 'CONFIRM', { row, x: -ACTION_X_M }, () => deck.practiceConfirm(performance.now(), 'poke')))) return;
      if (!add(await this.action(`Decision ${d.decision_id} skip`, 'SKIP FOR NOW', { row, x: ACTION_X_M }, () => deck.practiceSkip(performance.now(), 'poke')))) return;
      const project = rt.portfolio.stations.get(d.project ?? '');
      if (project && this.bringable(project) && !add(await this.bringAction(project))) return;
      if (!await this.addPager(add)) return;
      this.paintChoice(d);
    }
    this.busy = false;
    this.place();
  }

  /** Selection is shown in words as well as colour; the recommendation is never pre-marked. */
  private paintChoice(d: Decision): void {
    const card = this.card;
    if (!card) return;
    const selected = deck.practice.selected;
    const label = d.options?.find(o => o.id === selected)?.label;
    const skipped = deck.runtime?.portfolio.snoozed ?? 0;
    this.choiceText = `${label ? `YOUR CHOICE: ${label.toUpperCase()}` : 'CHOOSE ONE BELOW'}${skipped ? ` / ${skipped} SKIPPED` : ''}`;
    this.showPage();
    for (const s of this.options) {
      if (s.optionId) {
        const on = s.optionId === selected;
        this.set(s, 'option-label', `${on ? 'SELECTED: ' : ''}${s.cache.get('label') ?? ''}`, { color: on ? T.ion : T.ink });
        this.style(s, 'option-button', { borderColor: on ? T.ion : T.seamHi, backgroundColor: on ? STATUS.WORKING.dim : T.deck });
      } else if (s.doc.name.endsWith(' confirm')) {
        this.set(s, 'option-label', 'CONFIRM', { color: selected ? T.ion : T.ink3 });
        this.style(s, 'option-button', { borderColor: selected ? T.ion : T.seam });
      }
    }
  }

  update(): void {
    const now = performance.now();
    if (this.recentreIn > 0 && --this.recentreIn === 0) this.recentre();
    const tenths = Math.round(deck.hold.progress * 10);
    if (this.card && tenths !== this.holdTenths) {
      this.holdTenths = tenths;
      this.card.doc.getElementById('decision-hold-fill')?.setProperties({
        width: `${tenths * 10}%`, backgroundColor: deck.hold.pose === 'down' ? T.red : T.amber,
      } as never);
    }
    if (now < this.nextRefresh || this.busy) return;
    this.nextRefresh = now + 250;
    this.sounds.flush();
    const rt = deck.runtime;
    if (!rt || !this.card) return;
    const p = rt.portfolio;
    const stage = deck.standup?.stage;
    const guided = stage === 'summary' || stage === 'station' || stage === 'scrub';
    for (const [beat, ghost] of this.ghosts) {
      ghost.visible = beat === stage;
      if (!ghost.visible) continue;
      const pulse = Math.sin(now / 500);
      const tray = this.world.getSceneEntity('task-tray')?.object3D;
      if (beat === 'scrub' && tray) {
        ghost.position.copy(tray.position).addScaledVector(this.right, pulse * 0.10);
        ghost.position.y += 0.04;
      } else {
        ghost.position.copy(this.eye).addScaledVector(this.forward, 0.30).addScaledVector(this.right, 0.16);
        ghost.position.y -= beat === 'thumbs' ? 0.06 : 0.08 + pulse * 0.012;
      }
      ghost.quaternion.setFromUnitVectors(this.ghostForward, this.forward);
    }
    if (guided) {
      if (this.current !== stage) {
        this.current = stage;
        this.clearOptions();
        const p0 = rt.portfolio;
        const target = [...p0.stations.values()][centreStation(p0.stations.size)]?.name ?? 'a station';
        this.copy(this.card, tutorialCard(stage, deck.chiefFocus, deck.portfolioLine, target)!);
        this.style(this.card, 'decision-root', { borderColor: mix(T.ion, 0.55) });
        for (const [i, st] of [...this.stations.values()].entries()) this.paintRim(st, i);
      }
      this.place();
      return;
    }
    const view = deck.standup ? deck.practiceView(now) : null;
    if (view) {
      if (view.kind === 'choose') this.hadQueue = true;
      const key = view.kind === 'intro' ? 'intro' : view.kind === 'receipt' ? `receipt:${view.r.receipt_id}` : `choose:${view.d.decision_id}:${view.d.order}`;
      if (key !== this.current) {
        this.current = key;
        void this.renderPractice(view).catch(err => { this.busy = false; console.error(err); });
      } else if (view.kind === 'choose') this.paintChoice(view.d);
      for (const [id, s] of this.stations) {
        const station = p.stations.get(id);
        if (station) this.renderStation(station, s);
      }
      this.place();
      return;
    }
    const d = deck.standup ? deck.front(now) : p.queue[0];
    if (d) this.hadQueue = true;
    if (!d && this.hadQueue && (!deck.standup || rt.finished)) {
      this.hadQueue = false;
      this.summaryUntil = now + 6500;
      this.sounds.play('payoff', 'chief');
      deck.info(`inbox zero: answered=${p.answered} snoozed=${p.snoozed}`);
    }
    const key = d ? `${d.decision_id}:${d.order}` : `zero:${stage}`;
    if (key !== this.current) {
      this.current = key;
      void this.renderDecision(d).catch(err => { this.busy = false; console.error(err); });
    }
    if (d && d.decision_id !== 'tutorial') {
      const label = d.options?.find(o => o.id === d.default_option)?.label ?? 'not supplied';
      const hours = d.deadline_ms == null ? null : Math.max(0, (d.deadline_ms - rt.simTimeMs) / 3600000);
      this.set(this.card, 'decision-default', `DEFAULT ${label.toUpperCase()} / ${hours == null ? 'NO DEADLINE' : `IN ${hours.toFixed(1)} H`}`);
    }
    for (const [id, s] of this.stations) {
      const station = p.stations.get(id);
      if (station) this.renderStation(station, s);
    }
    this.place();
  }

  private renderStation(station: Station, s: Surface): void {
    const rt = deck.runtime!;
    const agents = [...station.rows.values()];
    const count = rt.portfolio.queue.filter(d => d.project === station.id).length;
    const key = stationState(agents, count, station.detail);
    const st = STATUS[key];
    this.set(s, 'station-name', station.name);
    this.set(s, 'station-status', key === 'FAILED' ? 'STALLED' : st.text, { color: st.c });
    this.style(s, 'station-chip', { borderColor: st.c, backgroundColor: st.dim });
    // One state per station: every row is worded from it, one row per role (#693).
    const rows = stationRows(agents, key).slice(0, 6);
    const rowCount = Math.max(1, rows.length);
    const days = station.deadline == null ? '' : ` ${Math.max(0, Math.ceil((station.deadline - Date.now()) / 86400000))}D`;
    const gate = gateLabel(station.gate, days);
    const height = stationHeightUnits(station.name, gate, rows);
    this.style(s, 'station-root', { height });
    for (let i = 0; i < 6; i++) {
      this.set(s, `station-agent-${i}`, rows[i] ?? 'COORDINATING', { display: i < rowCount ? 'flex' : 'none' });
    }
    const m = station.metrics;
    const f = (v: number) => Number(v.toPrecision(3));
    this.set(s, 'station-metric', m.length ? `METRIC ${f(m[0])} > ${f(m[m.length - 1])}` : '', { display: m.length ? 'flex' : 'none' });
    this.set(s, 'station-gate', gate, { display: gate ? 'flex' : 'none' });
    this.set(s, 'station-decisions', openLabel(count), { color: count ? T.amber : T.ink3 });
    this.set(s, 'promote-label', this.nearId === station.id ? returnLabel(deck.inspecting, this.current) : 'BRING HERE');
    // An idle station shows no BRING HERE; the guided station beat keeps its button (#699).
    const guided = deck.standup?.stage === 'station' && [...this.stations.values()].indexOf(s) === centreStation(rt.portfolio.stations.size);
    this.style(s, 'promote-button', { display: count > 0 || this.nearId === station.id || guided ? 'flex' : 'none' });
    this.paintRim(s, [...this.stations.values()].indexOf(s), count > 0);
    const metricKey = `${m.join(',')}|${height}`;
    if (s.cache.get('sparkline') !== metricKey) {
      s.cache.set('sparkline', metricKey);
      const old = s.doc.getObjectByName('sparkline') as Line | undefined;
      if (old) { s.doc.remove(old); old.geometry.dispose(); (old.material as LineBasicMaterial).dispose(); }
      if (m.length > 1) {
        // Drawn in the root's 170-unit bottom padding (local frame: units x pixel-size 0.005).
        const lo = Math.min(...m), range = Math.max(...m) - lo || 1;
        const bottom = -height * 0.005 / 2, half = (STATION_W_UNITS / 2 - 60) * 0.005;
        const vertices = m.flatMap((v, i) => [-half + i / (m.length - 1) * 2 * half, bottom + (40 + (v - lo) / range * 90) * 0.005, 0.01]);
        const line = new Line(new BufferGeometry().setAttribute('position', new Float32BufferAttribute(vertices, 3)), new LineBasicMaterial({ color: hex(T.ion) }));
        line.name = 'sparkline';
        s.doc.add(line);
      }
    }
  }

  /** Root border: amber while a decision is open, ion on the station the guided beat names. */
  private paintRim(s: Surface, index: number, open = s.cache.get('open') === '1'): void {
    s.cache.set('open', open ? '1' : '0');
    const p = deck.runtime?.portfolio;
    const lit = deck.standup?.stage === 'station' && !!p && index === centreStation(p.stations.size);
    this.style(s, 'station-root', { borderColor: lit ? T.ion : open ? T.amber : T.seam });
  }
  /** setProperties for style-only changes, deduplicated per element and property set. */
  private style(s: Surface, id: string, props: Record<string, unknown>): void {
    const value = JSON.stringify(props);
    if (s.cache.get(`style:${id}:${Object.keys(props).join(',')}`) === value) return;
    s.cache.set(`style:${id}:${Object.keys(props).join(',')}`, value);
    s.doc.getElementById(id)?.setProperties(props as never);
  }
  private endPaging(card: Surface): void {
    this.pages = [];
    this.page = 0;
    this.choiceText = null;
    this.set(card, 'decision-page', '-', { display: 'none' });
    this.style(card, 'decision-root', { height: 'auto' });
  }
  /** Decision and receipt copy split into pages of verbatim fields; the header stays on every page. */
  private pagedCopy(card: Surface, c: CardCopy, reserveChoice?: string): void {
    this.endPaging(card);
    this.set(card, 'decision-header', c.header);
    const headerLines = wrappedLines(ascii(c.header), WRAP.header);
    const blocks: TextBlock[] = [
      { id: 'decision-question', text: ascii(c.question), perLine: WRAP.question, lineUnits: 62.4, split: true },
      { id: 'decision-recommend', text: ascii(c.recommend), perLine: WRAP.recommend, lineUnits: 50.4, boxUnits: 34 },
      { id: 'decision-default', text: ascii(c.default), perLine: WRAP.default, lineUnits: 43.2, reserve: reserveChoice && ascii(reserveChoice) },
      { id: 'decision-hint', text: ascii(c.hint), perLine: WRAP.hint, lineUnits: 42.5 },
      { id: 'decision-evidence', text: ascii(c.evidence), perLine: WRAP.evidence, lineUnits: 48, split: true },
    ];
    const room = (paged: boolean) => CARD_MAX_UNITS - FIXED_CARD_UNITS - headerLines * HEADER_LINE_UNITS - 16 - (paged ? PAGE_LINE_UNITS : 0);
    const once = paginateText(blocks, room(false));
    this.pages = once.length > 1 ? paginateText(blocks, room(true)) : once;
    this.showPage();
  }
  /** Paints the current page; fields not on it collapse so the page reads top to bottom. */
  private showPage(): void {
    const card = this.card, current = this.pages[this.page];
    if (!card || !current) return;
    for (const id of ['decision-question', 'decision-recommend', 'decision-default', 'decision-evidence', 'decision-hint']) {
      const piece = current.find(b => b.id === id);
      const text = id === 'decision-default' && this.choiceText !== null ? this.choiceText : piece?.text;
      this.set(card, id, piece ? text ?? '-' : '-', { display: piece ? 'flex' : 'none', ...(id === 'decision-evidence' ? { whiteSpace: 'pre-line' } : {}) });
    }
    const n = this.pages.length;
    this.style(card, 'decision-root', { height: n > 1 ? CARD_MAX_UNITS : 'auto' });
    this.set(card, 'decision-page', n > 1 ? `PAGE ${this.page + 1} OF ${n} / ${this.page < n - 1 ? 'PRESS NEXT PAGE TO CONTINUE' : 'LAST PAGE'}` : '-', { display: n > 1 ? 'flex' : 'none' });
    const [more, back] = this.pager;
    if (more) this.set(more, 'option-label', 'NEXT PAGE', { color: this.page < n - 1 ? T.ion : T.ink3 });
    if (back) this.set(back, 'option-label', 'PREVIOUS PAGE', { color: this.page > 0 ? T.ink : T.ink3 });
  }
  private turnPage(delta: 1 | -1): void {
    const next = this.page + delta;
    if (next < 0 || next >= this.pages.length) return;
    this.page = next;
    deck.info(`decision text page ${next + 1}/${this.pages.length}`);
    this.showPage();
  }
  /** Near text controls in the right column, below Bring Here, only when the text needs pages. */
  private async addPager(add: (s: Surface) => boolean): Promise<boolean> {
    if (this.pages.length < 2) return true;
    const more = await this.action('Text more', 'NEXT PAGE', { row: 0, x: NAV_X_M }, () => this.turnPage(1));
    if (!add(more)) return false;
    const back = await this.action('Text previous', 'PREVIOUS PAGE', { row: 1, x: NAV_X_M }, () => this.turnPage(-1));
    if (!add(back)) return false;
    this.pager = [more, back];
    this.showPage();
    return true;
  }
  private copy(card: Surface, c: CardCopy): void {
    this.endPaging(card);
    this.set(card, 'decision-header', c.header);
    this.set(card, 'decision-question', c.question, { display: 'flex' });
    this.set(card, 'decision-recommend', c.recommend || '-', { display: c.recommend ? 'flex' : 'none' });
    for (const k of ['default', 'evidence', 'hint'] as const) this.set(card, `decision-${k}`, c[k] || '-', { display: c[k] ? 'flex' : 'none' });
  }

  recentre(): void {
    this.world.camera.getWorldPosition(this.eye);
    this.world.camera.getWorldDirection(this.forward);
    this.forward.y = 0;
    if (this.forward.lengthSq() < 1e-6) this.forward.set(0, 0, -1);
    this.forward.normalize();
    this.right.set(-this.forward.z, 0, this.forward.x);
    this.place();
  }
  private position(s: Surface, x: number, y: number, z: number): void {
    s.doc.position.copy(this.eye).addScaledVector(this.right, x).addScaledVector(this.forward, z);
    s.doc.position.y += y;
    s.doc.lookAt(this.eye);
  }
  private place(): void {
    const p = deck.runtime?.portfolio;
    if (!p) return;
    const stage = deck.standup?.stage;
    const view = deck.standup ? deck.practiceView(performance.now()) : null;
    const asking = deck.standup ? !!view || p.queue[0]?.decision_id === 'tutorial' : p.queue.length > 0;
    const inbox = asking || performance.now() < this.summaryUntil ||
      stage === 'summary' || stage === 'scrub' || stage === 'complete';
    // One clear task: the card plus at most its own project, until PROJECTS asks for the arc.
    const focus = !!view && !deck.showAllProjects;
    const contextId = view?.kind === 'choose' ? view.d.project : view?.kind === 'receipt' ? view.r.project : undefined;
    const guidedCard = inbox || stage === 'station';
    const inspecting = stationInspecting(p.promoted, this.inspectKey, this.current, inbox, stage);
    deck.inspecting = inspecting;
    this.nearId = p.promoted && (!inbox || inspecting) ? p.promoted : '';
    let i = 0;
    for (const [id, s] of this.stations) {
      const near = id === this.nearId;
      let [x, y, z] = stationPosition(i++, p.stations.size);
      const isContext = focus && id === contextId;
      s.doc.visible = !focus || isContext || near;
      if (!s.doc.visible) { s.doc.position.y = -50; continue; }
      if (isContext) {
        const a = CONTEXT_STATION_DEG * Math.PI / 180;
        x = Math.sin(a) * STATION_ARC_M;
        z = Math.cos(a) * STATION_ARC_M;
      }
      s.doc.scale.setScalar(near ? PROMOTED_SCALE : STATION_SCALE);
      this.position(s, near ? 0 : x, near ? -0.06 : y, near ? Math.sqrt(NEAR_M ** 2 - 0.06 ** 2) : z);
    }
    if (this.card) {
      this.card.doc.visible = guidedCard && !inspecting;
      // Card above the option column; both clear the tray's top edge (about -0.27 m).
      const baseY = view ? PRACTICE_CARD_Y : stage === 'summary' || stage === 'station' || stage === 'scrub' ? 0.0 : 0.05;
      const cardY = this.options.length ? cardCentreY(baseY, this.columnTopM(view), this.cardHeightM()) : baseY;
      this.position(this.card, 0, cardY, Math.sqrt(NEAR_M ** 2 - cardY ** 2));
      if (!this.card.doc.visible) this.card.doc.position.y = -50;
    }
    this.options.forEach((s, index) => {
      s.doc.visible = !inspecting;
      if (inspecting) { s.doc.position.y = -50; return; }
      this.placeNear(s, (view ? PRACTICE_OPTION_Y : -0.07) - (s.slot?.row ?? index) * OPTION_PITCH_M);
    });
    this.placeNav(p, stage);
    this.placeBackdrop(!!view, inspecting);
  }
  /** Top edge of the option column; the card's measured bottom stays above it. */
  private columnTopM(view: PracticeView | null): number {
    return (view ? PRACTICE_OPTION_Y : -0.07) + OPTION_HALF_M;
  }
  /** Rendered card height: long fields wrap, so a fixed centre would run into the options. */
  private cardHeightM(): number {
    const root = this.card?.doc.getElementById('decision-root') as unknown as { size?: { value?: [number, number] } } | undefined;
    return (root?.size?.value?.[1] ?? 0) * NEAR_UNIT_M;
  }
  private placeBackdrop(practice: boolean, inspecting: boolean): void {
    const column = this.options.filter(s => Math.abs(s.slot?.x ?? 0) < COLUMN_HALF_M).map((s, i) => s.slot?.row ?? i);
    const shown = !!this.card?.doc.visible && !inspecting && column.length > 0;
    this.backdrop.visible = shown;
    if (!shown) { this.backdrop.position.y = -50; return; }
    const y0 = practice ? PRACTICE_OPTION_Y : -0.07;
    const lowest = y0 - Math.max(...column) * OPTION_PITCH_M - OPTION_HALF_M;
    const h = this.cardHeightM();
    const cardY = this.card!.doc.position.y - this.eye.y;
    const plate = decisionBackdrop({ halfWidth: COLUMN_HALF_M, top: cardY + h / 2, bottom: lowest, near: NEAR_M, z: BACKDROP_Z_M });
    this.backdrop.position.copy(this.eye).addScaledVector(this.forward, BACKDROP_Z_M);
    this.backdrop.position.y += plate.y;
    this.backdrop.scale.set(plate.width, plate.height, 1);
    this.backdrop.lookAt(this.eye.x, this.backdrop.position.y, this.eye.z);
  }
  private placeNear(s: Surface, y: number): void {
    this.position(s, s.slot?.x ?? 0, y, Math.sqrt(Math.max(0.01, NEAR_M ** 2 - y ** 2)));
    // Side-by-side actions stay parallel to the column (a yawed panel stops a poke short).
    if (s.slot?.x) s.doc.lookAt(this.eye.x + this.right.x * s.slot.x, this.eye.y, this.eye.z + this.right.z * s.slot.x);
  }
  private placeNav(p: Portfolio, stage?: string): void {
    const shown = !!this.nearId && !guidedStage(stage);
    for (const s of this.nav) {
      s.doc.visible = shown;
      if (!shown) { s.doc.position.y = -50; continue; }
      this.placeNear(s, NAV_ORIGIN_Y - s.slot!.row * OPTION_PITCH_M);
    }
    if (!shown || this.nav.length < 4) return;
    const ids = [...p.stations.keys()];
    this.set(this.nav[0], 'option-label', projectPositionLabel(ids.indexOf(this.nearId), ids.length, p.stations.get(this.nearId)!.name), { color: T.ink });
    this.set(this.nav[3], 'option-label', returnLabel(deck.inspecting, this.current), { color: T.ion });
  }
}
