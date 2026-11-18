/**
 * In-headset replay of an agent-event fixture (agents/schema/README.md).
 *
 * Semantics mirror agents/bridge/src/replay.ts:
 * - each agent has its own clock (lane offset), so pausing one agent never stalls the others;
 * - status=INPUT_REQUIRED pauses only the asking agent until answer(), or until
 *   autoAnswerS real seconds pass, when the default option is chosen;
 * - a step_start whose depends_on names a step that ends later in the fixture is held
 *   until that step_end has been emitted (it can lag when its agent is paused);
 * - headset task_assigned messages are echoed as task_assigned events.
 * No DOM or IWSDK imports: it runs under node for the fixture smoke test.
 */

import { Portfolio, type Decision } from './portfolio.ts';

export type Status =
  | 'SUBMITTED'
  | 'WORKING'
  | 'INPUT_REQUIRED'
  | 'COMPLETED'
  | 'FAILED'
  | 'CANCELED';

export interface AgentOption {
  id: string;
  label: string;
  gesture?: string;
}

export interface AgentEvent {
  project?: string;
  project_name?: string;
  decision_id?: string;
  recommendation?: string;
  blocking?: boolean;
  evidence?: string;
  deadline_ms?: number;
  name?: string;
  value?: number;
  t_ms: number;
  agent_id: string;
  kind: string;
  agent_name?: string;
  run_id?: string;
  step_id?: string;
  parent_step_id?: string | null;
  depends_on?: string[];
  title?: string;
  detail?: string;
  status?: Status;
  tool?: string;
  options?: AgentOption[];
  default_option?: string;
  answer?: string;
  plan?: string[];
  priority?: string;
}

export interface StepRow {
  id: string;
  title: string;
  status: Status;
}

export interface Question {
  step_id: string;
  title: string;
  detail?: string;
  options: AgentOption[];
  default_option: string;
  askedAtReal: number;
}

export interface AgentState {
  id: string;
  name: string;
  status: Status;
  task: string;
  currentStep: string;
  steps: StepRow[];
  question: Question | null;
  /** One-line note: last answer, recording mismatch, error or held dependency. */
  note: string;
  held: boolean;
}

export type RuntimeEvent =
  | { type: 'event'; event: AgentEvent }
  | { type: 'state'; agent: AgentState }
  | { type: 'finished' };

interface Lane {
  q: AgentEvent[];
  i: number;
  offset: number;
  blocked: Question | null;
  heldOn: string | null;
  userChoice: string | null;
}

const ROLE_NAMES: Record<string, string> = {
  researcher: 'Researcher',
  builder: 'Builder',
  reviewer: 'Reviewer',
};
export const ROLES = ['researcher', 'builder', 'reviewer'] as const;
const KEEP_STEPS = 6;

export function parseJsonl(text: string): AgentEvent[] {
  return text
    .split(/\r?\n/u)
    .filter((l) => l.trim().length > 0)
    .map((l) => JSON.parse(l) as AgentEvent);
}

/**
 * Stand-up pacing (decisions.md 2026-11-04 14:30): wall_gap = min(real_gap / speed, GAP_CAP).
 * GAP_CAP is 0.15 s at 30x and scales as 30 / speed, so it always equals 4.5 s of recorded time.
 * Idle hours collapse, bursts play at speed, nothing is dropped or reordered and every event keeps
 * its recorded t_ms; each collapse is recorded in `skips` so the scrubber can show it.
 */
export const GAP_CAP_MS = 150;
/** Collapsed gaps at least this long are named on the clock line ("SKIPPED 2 HR 14 MIN OF QUIET"). */
export const SKIP_MARK_MS = 60_000;
/** Idle stretches of the recording at least this long are drawn on the scrubber. */
export const IDLE_SPAN_MS = 30 * 60_000;
export interface Skip { fromMs: number; toMs: number }
export const skipLabel = (s: Skip): string => {
  const m = Math.round((s.toMs - s.fromMs) / 60000);
  return `SKIPPED ${m >= 60 ? `${Math.floor(m / 60)} HR ${m % 60} MIN` : `${m} MIN`} OF QUIET`;
};

export interface ReplayOptions {
  speed: number;
  standup?: boolean;
  startMs?: number;
  answers?: Record<string, string>;
  /** Stand-up only: a recorded decision shown first. Events still play in recorded order. */
  openingDecision?: string;
  /** Real seconds before INPUT_REQUIRED takes the default option; null = hold forever. */
  autoAnswerS: number | null;
}

export class ReplayRuntime {
  readonly portfolio: Portfolio;
  readonly agents = new Map<string, AgentState>();
  private lanes = new Map<string, Lane>();
  private endsLater = new Set<string>();
  private ended = new Set<string>();
  private listeners: ((e: RuntimeEvent) => void)[] = [];
  private simMs = 0;
  private lastReal: number | null = null;
  /** Wall ms the clock has advanced since the last emitted event (stand-up pacing). */
  private idleWall = 0;
  private lastEventMs: number;
  readonly skips: Skip[] = [];
  /** Idle stretches (>= IDLE_SPAN_MS) of the recording, real clock; the scrubber draws them. */
  readonly idleGaps: Skip[] = [];
  finished = false;
  emitted = 0;
  paused = false;
  /** A practice receipt is on screen: the recording waits for a deliberate Next. */
  receiptHold = false;
  readonly answers: Record<string, string>;
  private deferred = new Set<string>();
  private lead?: string;
  /** The opening decision has not been reached yet; until it has, earlier asks wait behind it. */
  private leadPending = false;
  readonly startMs: number;
  readonly endMs: number;

  opts: ReplayOptions;

  constructor(events: AgentEvent[], opts: ReplayOptions) {
    this.opts = opts;
    this.answers = { ...opts.answers };
    this.startMs = Math.min(...events.map(e => e.t_ms));
    this.endMs = Math.max(...events.map(e => e.t_ms));
    this.lastEventMs = this.startMs;
    const ts = events.map(e => e.t_ms).sort((a, b) => a - b);
    for (let k = 1; k < ts.length; k++) if (ts[k] - ts[k - 1] >= IDLE_SPAN_MS) this.idleGaps.push({ fromMs: ts[k - 1], toMs: ts[k] });
    this.lead = opts.standup && events.some(e => e.kind === 'decision' && e.decision_id === opts.openingDecision) ? opts.openingDecision : undefined;
    this.leadPending = !!this.lead;
    this.portfolio = new Portfolio(events, this.lead);
    for (const id of this.portfolio.stations.size ? [] : ROLES) this.agents.set(id, blankAgent(id));
    for (const e of events) {
      if (!this.agents.has(e.agent_id)) this.agents.set(e.agent_id, blankAgent(e.agent_id));
      if (!this.lanes.has(e.agent_id)) {
        this.lanes.set(e.agent_id, {
          q: [],
          i: 0,
          offset: 0,
          blocked: null,
          heldOn: null,
          userChoice: null,
        });
      }
      this.lanes.get(e.agent_id)!.q.push(e);
      if (e.kind === 'step_end' && e.step_id) this.endsLater.add(e.step_id);
    }
    if (opts.standup) {
      this.portfolio.answered = events.filter(e => e.kind === 'decision' && e.decision_id && this.answers[e.decision_id]).length;
      this.simMs = Math.max(this.startMs, Math.min(opts.startMs ?? this.startMs, this.endMs));
      // Reconstruct without sound/listener side effects; retain every unanswered ask.
      for (const e of [...events].sort((a, b) => a.t_ms - b.t_ms)) {
        if (e.t_ms > this.simMs) break;
        const lane = this.lanes.get(e.agent_id)!;
        lane.i++;
        this.apply(lane, e);
      }
      this.lastEventMs = this.simMs;
    }
  }

  on(fn: (e: RuntimeEvent) => void): () => void {
    this.listeners.push(fn);
    return () => {
      this.listeners = this.listeners.filter((l) => l !== fn);
    };
  }

  get simTimeMs(): number {
    return this.simMs;
  }

  setSpeed(speed: number): void {
    this.opts.speed = speed;
  }

  /** Advance to real time nowMs (performance.now()). */
  tick(nowMs: number): void {
    if (this.finished) return;
    if (this.lastReal == null) this.lastReal = nowMs;
    if (!this.paused && !this.decisionHold) {
      this.simMs += (nowMs - this.lastReal) * this.opts.speed;
      this.idleWall += nowMs - this.lastReal;
    }
    if (this.opts.standup) this.simMs = Math.min(this.simMs, this.endMs);
    this.lastReal = nowMs;
    if (this.paused) return;

    for (const d of this.portfolio.queue) {
      if ((d.deadline_ms != null && this.simMs >= d.deadline_ms) ||
        (this.opts.autoAnswerS != null && nowMs - d.shownAt >= this.opts.autoAnswerS * 1000)) {
        this.answerDecision(d.decision_id!, d.default_option!, 'auto');
      }
    }

    for (const [id, lane] of this.lanes) {
      const q = lane.blocked;
      if (q && this.opts.autoAnswerS != null) {
        if (nowMs - q.askedAtReal >= this.opts.autoAnswerS * 1000) {
          this.answer(id, q.default_option, 'auto');
        }
      }
    }

    // Pacing: once the clock has idled GAP_CAP of wall time, jump to the next event.
    if (this.opts.standup && !this.decisionHold && this.idleWall >= GAP_CAP_MS * 30 / this.opts.speed) {
      const next = this.nextEventMs();
      if (next != null && next > this.simMs) {
        this.skips.push({ fromMs: this.lastEventMs, toMs: next });
        this.simMs = next;
      }
    }

    // Merge lanes in effective-time order so a step_end emitted in this tick can release a
    // depends_on hold in the same tick.
    for (;;) {
      if (this.decisionHold) break;
      let best: { lane: Lane; t: number } | null = null;
      for (const lane of this.lanes.values()) {
        if (lane.blocked || lane.i >= lane.q.length) continue;
        const e = lane.q[lane.i];
        const t = e.t_ms + lane.offset;
        if (t > this.simMs) continue;
        if (this.isHeld(lane, e)) continue;
        if (!best || t < best.t) best = { lane, t };
      }
      if (!best) break;
      const { lane } = best;
      const e = lane.q[lane.i++];
      this.apply(lane, e);
      this.idleWall = 0;
      this.lastEventMs = best.t;
      if (this.decisionHold) this.simMs = e.t_ms;
    }

    if (!this.finished && !this.portfolio.queue.length && [...this.lanes.values()].every((l) => l.i >= l.q.length && !l.blocked)) {
      this.finished = true;
      this.emit({ type: 'finished' });
    }
  }

  /** Effective time of the next event that could fire (no side effects). */
  private nextEventMs(): number | null {
    let next: number | null = null;
    for (const lane of this.lanes.values()) {
      if (lane.blocked || lane.i >= lane.q.length) continue;
      const e = lane.q[lane.i];
      if (e.kind === 'step_start' && (e.depends_on ?? []).some(d => this.endsLater.has(d) && !this.ended.has(d))) continue;
      const t = e.t_ms + lane.offset;
      if (next == null || t < next) next = t;
    }
    return next;
  }

  /** Collapsed gaps long enough to mark on the scrubber. */
  get markedSkips(): Skip[] {
    return this.skips.filter(s => s.toMs - s.fromMs >= SKIP_MARK_MS);
  }

  private isHeld(lane: Lane, e: AgentEvent): boolean {
    const waiting =
      e.kind === 'step_start'
        ? (e.depends_on ?? []).find((d) => this.endsLater.has(d) && !this.ended.has(d))
        : undefined;
    const agent = this.agents.get(e.agent_id);
    if (waiting) {
      if (lane.heldOn !== waiting) {
        lane.heldOn = waiting;
        if (agent) {
          agent.held = true;
          agent.note = `Waiting on ${waiting}`;
          this.emit({ type: 'state', agent });
        }
      }
      return true;
    }
    if (lane.heldOn) {
      // Released: shift this agent's clock so the rest of its track keeps its spacing.
      lane.heldOn = null;
      lane.offset = Math.max(lane.offset, this.simMs + 200 - e.t_ms);
      if (agent) {
        agent.held = false;
        agent.note = '';
      }
    }
    return false;
  }

  /** Answer the pending question of agentId. Returns false if nothing is pending. */
  answer(agentId: string, optionId: string, who: 'user' | 'auto' = 'user'): boolean {
    const lane = this.lanes.get(agentId);
    const agent = this.agents.get(agentId);
    if (!lane?.blocked || !agent) return false;
    const q = lane.blocked;
    if (!q.options.some((o) => o.id === optionId)) return false;
    lane.blocked = null;
    lane.userChoice = optionId;
    const next = lane.q[lane.i];
    if (next) lane.offset = Math.max(lane.offset, this.simMs + 300 - next.t_ms);
    const label = q.options.find((o) => o.id === optionId)?.label ?? optionId;
    agent.question = null;
    agent.note = `${who === 'user' ? 'You chose' : 'Auto-answered'}: ${label}`;
    this.emit({
      type: 'event',
      event: {
        t_ms: Math.round(this.simMs),
        agent_id: agentId,
        kind: 'input_response',
        step_id: q.step_id,
        answer: optionId,
        title: agent.note,
      },
    });
    this.emit({ type: 'state', agent });
    return true;
  }

  /** Headset -> runtime task_assigned, echoed as an event (bridge semantics). */
  assignTask(agentId: string, task: string, priority = 'normal'): void {
    const e: AgentEvent = {
      t_ms: Math.round(this.simMs),
      agent_id: agentId,
      kind: 'task_assigned',
      title: task,
      priority,
      status: 'SUBMITTED',
    };
    this.apply(null, e);
  }

  pending(agentId: string): Question | null {
    return this.lanes.get(agentId)?.blocked ?? null;
  }

  get decisionHold(): boolean {
    return !!this.opts.standup && (this.receiptHold || !this.leadPending && this.portfolio.queue.some(d => !this.deferred.has(d.decision_id!)));
  }

  /** Every recorded event has been emitted. */
  get drained(): boolean {
    return [...this.lanes.values()].every(l => l.i >= l.q.length && !l.blocked);
  }

  /** The card to ask now: skipped cards wait until the recording has played out, then return. */
  frontDecision(): Decision | undefined {
    if (this.leadPending) return undefined;
    const q = this.portfolio.queue;
    return q.find(d => !this.deferred.has(d.decision_id!)) ?? (this.drained ? q[0] : undefined);
  }

  snoozeDecision(id: string, now: number): boolean {
    if (!this.portfolio.snooze(id, now)) return false;
    this.deferred.add(id);
    return true;
  }

  answerDecision(id: string, option: string, who: 'user' | 'auto' = 'user'): boolean {
    const d = this.portfolio.decisions.get(id);
    if (!d || !d.options?.some((o) => o.id === option)) return false;
    this.portfolio.decisions.delete(id);
    this.answers[id] = option;
    this.portfolio.answered++;
    this.emit({ type: 'event', event: {
      kind: 'input_response', t_ms: Math.round(this.simMs), agent_id: d.agent_id,
      project: d.project, decision_id: id, answer: option,
      title: `${who === 'user' ? 'You chose' : 'Auto-answered'}: ${option}`,
    } });
    return true;
  }

  private apply(lane: Lane | null, e: AgentEvent): void {
    this.emitted++;
    this.portfolio.apply(e, this.lastReal ?? 0);
    if (e.kind === 'decision' && e.decision_id === this.lead) this.leadPending = false;
    if (e.decision_id && this.answers[e.decision_id]) this.portfolio.decisions.delete(e.decision_id);
    const a = this.agents.get(e.agent_id);
    this.emit({ type: 'event', event: e });
    if (!a) return;
    if (e.agent_name) a.name = e.agent_name;
    switch (e.kind) {
      case 'task_assigned':
        a.task = e.title ?? '';
        if (!lane) a.note = `New task: ${e.title ?? ''}`;
        break;
      case 'status':
        if (e.status) a.status = e.status;
        if (e.status === 'INPUT_REQUIRED' && lane && !e.decision_id) {
          const q: Question = {
            step_id: e.step_id ?? '',
            title: e.title ?? 'Input required',
            detail: e.detail,
            options: e.options ?? [],
            default_option: e.default_option ?? e.options?.[0]?.id ?? 'approve',
            askedAtReal: this.lastReal ?? 0,
          };
          lane.blocked = q;
          a.question = q;
        } else if (e.status === 'WORKING' && e.answer !== undefined && lane) {
          // Recording continues as recorded; say so when the user picked differently.
          if (lane.userChoice && lane.userChoice !== e.answer) {
            const rec = lastOptions(lane)?.find((o) => o.id === e.answer)?.label ?? e.answer;
            a.note = `In the recording, the developer chose: ${rec}`;
          }
          lane.userChoice = null;
        }
        break;
      case 'step_start':
        if (e.step_id) {
          a.currentStep = e.title ?? e.step_id;
          a.steps.push({ id: e.step_id, title: a.currentStep, status: 'WORKING' });
          if (a.steps.length > KEEP_STEPS) a.steps.splice(0, a.steps.length - KEEP_STEPS);
        }
        break;
      case 'step_end': {
        if (e.step_id) this.ended.add(e.step_id);
        const row = a.steps.find((s) => s.id === e.step_id);
        if (row && e.status) row.status = e.status;
        break;
      }
      case 'error':
        a.note = `Error: ${e.title ?? ''}`;
        break;
      case 'done':
        if (e.status) a.status = e.status;
        break;
      default:
        return; // tool_call/tool_result/plan/message/artifact: no panel state change
    }
    this.emit({ type: 'state', agent: a });
  }

  private emit(e: RuntimeEvent): void {
    for (const l of this.listeners) l(e);
  }
}

function lastOptions(lane: Lane): AgentOption[] | undefined {
  for (let i = lane.i - 1; i >= 0; i--) {
    const e = lane.q[i];
    if (e.kind === 'status' && e.status === 'INPUT_REQUIRED') return e.options;
  }
  return undefined;
}

function blankAgent(id: string): AgentState {
  return {
    id,
    name: ROLE_NAMES[id] ?? id,
    status: 'SUBMITTED',
    task: '',
    currentStep: '',
    steps: [],
    question: null,
    note: '',
    held: false,
  };
}
