/**
 * Shared app state: the bundled fixtures, the live ReplayRuntime and the tray selection.
 * URL overrides for scripted tests: ?fixture=<name>&speed=<n>&auto=<seconds>.
 */
import { ReplayRuntime, parseJsonl, type AgentEvent, type AgentState } from './replay.js';
import { readStandup, writeStandup, StandupSession, portfolioLine } from './standup.js';
import { OPENING_DECISION, PracticeFlow, readPractice, writePractice, type PracticeReceipt } from './practice.js';
import type { Decision } from './portfolio.js';

// Bundled at build time (one small chunk per fixture), read straight from the schema repo.
const loaders = import.meta.glob('./recordings/*.jsonl', {
  query: '?raw',
  import: 'default',
}) as Record<string, () => Promise<string>>;

export const FIXTURES = [
  'real_autoflow_day',
];
/** Only real captures may say "recorded" (docs/design.md s6). */
const RECORDED = new Set(['real_autoflow_day']);

const params = new URLSearchParams(location.search);
let storage: Storage | undefined;
try { storage = window.localStorage; } catch { /* Storage is optional. */ }
const saved = readStandup(storage);
const firstFrame = performance.now();

export type PracticeView = { kind: 'intro' } | { kind: 'choose'; d: Decision } | { kind: 'receipt'; r: PracticeReceipt };

export const deck = {
  fixture: FIXTURES.includes(params.get('fixture') ?? '')
    ? params.get('fixture')!
    : 'real_autoflow_day',
  speed: Number(params.get('speed') ?? '30') || 30,
  standup: null as StandupSession | null,
  events: [] as AgentEvent[],
  chiefFocus: '',
  portfolioLine: '',
  /** Thumbs hold on the front decision (panels.ts gate -> decision card hold bar). */
  hold: { progress: 0, pose: null as string | null },
  saved,
  /** Recorded first-minute state: explicit choice, local receipts, deliberate Next. */
  practice: new PracticeFlow(readPractice(storage)),
  /** Tray PROJECTS: the full station arc beside the card instead of the one project. */
  showAllProjects: false,
  /** A station is standing in for the open card; thumbs must not answer a card out of view. */
  inspecting: false,
  autoAnswerS: Number(params.get('auto') ?? '45') || 45,
  runtime: null as ReplayRuntime | null,
  /** role id of the selected tray card, or null */
  selectedCard: null as string | null,
  assigned: new Set<string>(),
  log: [] as string[],
  /** Tray sound toggle; ?mute=1 starts muted. */
  muted: params.get('mute') === '1',
  private: {
    stateFns: [] as ((a: AgentState) => void)[],
    resetFns: [] as (() => void)[],
    eventFns: [] as ((e: AgentEvent) => void)[],
    highlightFns: [] as ((role: string) => void)[],
  },

  modeLine(): string {
    const src = RECORDED.has(this.fixture)
      ? 'Replayed from a real coding-agent run'
      : 'Scripted scenario';
    return this.speed === 1 ? src : `${src} - REPLAY ${this.speed}x`;
  },

  onState(fn: (a: AgentState) => void): void {
    this.private.stateFns.push(fn);
  },
  onReset(fn: () => void): void {
    this.private.resetFns.push(fn);
  },
  /** Every runtime event (graph view). */
  onEvent(fn: (e: AgentEvent) => void): void {
    this.private.eventFns.push(fn);
  },
  /** "Jump to agent" from the node card: highlight the owning panel. */
  onHighlight(fn: (role: string) => void): void {
    this.private.highlightFns.push(fn);
  },
  highlight(role: string): void {
    this.info(`jump to agent ${role}`);
    for (const fn of this.private.highlightFns) fn(role);
  },

  info(msg: string): void {
    const line = `[overhand] ${msg}`;
    this.log.push(line);
    console.info(line);
  },

  async load(name: string, position?: number): Promise<void> {
    const key = Object.keys(loaders).find((k) => k.endsWith(`/${name}.jsonl`));
    if (!key) throw new Error(`fixture not bundled: ${name}`);
    const events = parseJsonl(await loaders[key]());
    this.events = events;
    const morning = name === 'real_autoflow_day';
    if (position == null) this.standup = morning ? new StandupSession(firstFrame, saved.tutorial) : null;
    if (position == null && this.standup) {
      this.standup.stageAt = performance.now();
      this.standup.stage = this.practice.introDone ? 'replay' : 'intro';
    }
    if (morning) this.practice.reconcile(events, saved.answers);
    const chief = events.filter(e => e.project === 'chief' && e.kind === 'message');
    const focus = chief.filter(e => e.title === 'focus');
    this.chiefFocus = focus[focus.length - 1]?.detail ?? 'Focus not supplied';
    this.portfolioLine = portfolioLine(events);
    this.fixture = name;
    this.selectedCard = null;
    this.assigned.clear();
    const rt = new ReplayRuntime(events, {
      speed: this.speed,
      autoAnswerS: events.some(e => e.project) && !params.has('auto') ? null : this.autoAnswerS,
      standup: morning,
      startMs: morning ? position ?? saved.position : undefined,
      answers: morning ? saved.answers : undefined,
      openingDecision: morning ? OPENING_DECISION : undefined,
    });
    rt.on((e) => {
      if (e.type === 'event') for (const fn of this.private.eventFns) fn(e.event);
      if (e.type === 'state') {
        for (const fn of this.private.stateFns) fn(e.agent);
      } else if (e.type === 'finished') {
        this.info(`fixture ${name} finished: ${rt.emitted} events`);
        if (this.standup) this.info(`standup_minutes=${this.standup.complete(performance.now()).toFixed(3)}`);
      } else if (
        e.event.kind === 'input_response' ||
        e.event.kind === 'decision' ||
        e.event.kind === 'task_assigned' ||
        (e.event.kind === 'status' && e.event.status === 'INPUT_REQUIRED') ||
        e.event.kind === 'done'
      ) {
        if (e.type === 'event' && e.event.kind === 'input_response' && morning) {
          if (e.event.decision_id === 'tutorial') {
            rt.portfolio.answered--;
            delete rt.answers.tutorial;
            this.advanceTutorial();
          }
          this.persist();
        }
        this.info(
          `t=${Math.round(e.event.t_ms / 1000)}s ${e.event.agent_id} ${e.event.kind} ${e.event.status ?? ''} ${e.event.title ?? ''} ${e.event.decision_id ? `decision_id=${e.event.decision_id} answer=${e.event.answer ?? '-'}` : ''}`,
        );
      }
    });
    this.runtime = rt;
    rt.receiptHold = morning && !!this.practice.held;
    rt.paused = !!this.standup && this.standup.stage !== 'replay';
    if (morning && saved.position && position == null) this.info(`standup resume last_seen=${saved.position} tutorial=${saved.tutorial ? 'skipped' : 'pending'}`);
    this.info(`loaded ${name} (${events.length} events) at ${this.speed}x`);
    for (const fn of this.private.resetFns) fn();
    for (const a of rt.agents.values()) for (const fn of this.private.stateFns) fn(a);
  },

  persist(): void {
    if (!this.standup || !this.runtime) return;
    saved.position = this.runtime.simTimeMs;
    saved.answers = Object.fromEntries(Object.entries(this.runtime.answers).filter(([id]) => id !== 'tutorial'));
    saved.tutorial = this.standup.tutorialDone;
    writeStandup(storage, saved);
  },
  /** The card the headset asks now; a held receipt and skipped cards hold it back. */
  front(now: number): Decision | undefined {
    const rt = this.runtime;
    if (!rt) return undefined;
    const first = rt.portfolio.queue[0];
    if (!this.standup || first?.decision_id === 'tutorial') return first;
    if (this.practice.held) return undefined;
    const d = rt.frontDecision();
    if (d) this.practice.show(`${d.decision_id}:${d.order}`, now);
    return d;
  },
  practiceView(now: number): PracticeView | null {
    const s = this.standup, rt = this.runtime;
    if (!s || !rt) return null;
    if (s.stage === 'intro') return { kind: 'intro' };
    if (rt.portfolio.queue[0]?.decision_id === 'tutorial') return null;
    if (this.practice.held) return { kind: 'receipt', r: this.practice.held };
    const d = this.front(now);
    return d ? { kind: 'choose', d } : null;
  },
  savePractice(): void {
    writePractice(storage, this.practice.save);
  },
  practiceBegin(): void {
    if (this.standup?.stage !== 'intro') return;
    this.practice.introDone = true;
    this.savePractice();
    this.info('practice begin');
    this.advanceTutorial();
  },
  practiceSelect(d: Decision, option: string, now: number): boolean {
    const ok = this.front(now)?.decision_id === d.decision_id && this.practice.select(d, option, now);
    this.info(`practice select decision_id=${d.decision_id} option=${option} ${ok ? 'selected' : 'ignored'}`);
    return ok;
  },
  practiceConfirm(now: number, how: string): boolean {
    const rt = this.runtime, d = this.front(now);
    if (!rt || !d || d.decision_id === 'tutorial') return false;
    const receipt = this.practice.confirm(d, now);
    if (!receipt) {
      this.info(`practice confirm ignored decision_id=${d.decision_id} selected=${this.practice.selected ?? 'none'} via=${how}`);
      return false;
    }
    rt.receiptHold = true;
    this.savePractice();
    this.info(`practice receipt ${receipt.receipt_id} decision_id=${receipt.decision_id} option=${receipt.option_id} via=${how}`);
    rt.answerDecision(receipt.decision_id, receipt.option_id);
    this.persist();
    return true;
  },
  practiceSkip(now: number, how: string): boolean {
    const rt = this.runtime, d = this.front(now);
    if (!rt || !d || !this.practice.armed(now)) return false;
    this.practice.selected = null;
    rt.snoozeDecision(d.decision_id!, now);
    this.info(`practice skip decision_id=${d.decision_id} via=${how} (no receipt)`);
    return true;
  },
  practiceNext(now: number): boolean {
    const rt = this.runtime;
    if (!rt || !this.practice.next(now)) { this.info('practice next ignored'); return false; }
    rt.receiptHold = false;
    this.savePractice();
    this.info('practice next');
    return true;
  },
  toggleProjects(): void {
    this.showAllProjects = !this.showAllProjects;
    this.info(`all projects ${this.showAllProjects ? 'shown' : 'hidden'}`);
  },
  advanceTutorial(): void {
    const s = this.standup;
    if (!s) return;
    this.runtime?.portfolio.decisions.delete('tutorial');
    s.advance(performance.now());
    this.info(`standup stage=${s.stage}`);
    this.syncStandup();
  },
  syncStandup(): void {
    const s = this.standup, rt = this.runtime;
    if (!s || !rt) return;
    rt.paused = s.stage !== 'replay' && s.stage !== 'complete';
    if (s.stage !== 'thumbs') rt.portfolio.decisions.delete('tutorial');
    if (s.stage === 'thumbs' && !rt.portfolio.decisions.has('tutorial')) rt.portfolio.apply({
      kind: 'decision', decision_id: 'tutorial', project: 'chief', agent_id: 'tutorial',
      t_ms: rt.simTimeMs, status: 'INPUT_REQUIRED', title: 'Ready for your morning stand-up?',
      options: [{ id: 'ready', label: 'Start the day replay' }], recommendation: 'ready', default_option: 'ready',
    }, performance.now());
    const practice = rt.portfolio.decisions.get('tutorial');
    if (practice) practice.order = -1;
    this.persist();
  },
  replayTutorial(): void {
    this.practice.introDone = true;
    this.savePractice();
    this.standup = new StandupSession(performance.now(), false);
    if (this.runtime) this.runtime.finished = false;
    this.info('standup tutorial replay stage=summary');
    this.syncStandup();
  },
  seek(fraction: number): void {
    const rt = this.runtime;
    if (!rt || !this.standup) return;
    if (this.standup.stage === 'scrub') { this.info('standup tutorial pinch-drag complete'); this.advanceTutorial(); return; }
    this.persist();
    const position = rt.startMs + Math.max(0, Math.min(1, fraction)) * (rt.endMs - rt.startMs);
    this.info(`standup scrub last_seen=${Math.round(position)}`);
    void this.load(this.fixture, position).then(() => this.persist());
  },

  nextFixture(): Promise<void> {
    const i = FIXTURES.indexOf(this.fixture);
    return this.load(FIXTURES[(i + 1) % FIXTURES.length]);
  },

  toggleMute(): void {
    this.muted = !this.muted;
    this.info(`sound ${this.muted ? 'off' : 'on'}`);
  },

  toggleSpeed(): void {
    this.speed = this.standup ? this.speed === 10 ? 30 : this.speed === 30 ? 60 : 10 : this.speed === 1 ? 4 : 1;
    this.runtime?.setSpeed(this.speed);
    this.info(`speed ${this.speed}x`);
    if (this.runtime) {
      for (const a of this.runtime.agents.values())
        for (const fn of this.private.stateFns) fn(a);
    }
  },
};

(window as unknown as { __overhand: typeof deck }).__overhand = deck;
