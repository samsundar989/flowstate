/**
 * Recorded first-minute practice: explicit choice, one local receipt per decision, deliberate Next.
 * Nothing here leaves the device and no agent is contacted. No DOM or IWSDK imports (node tests).
 */
import type { AgentEvent } from './replay.ts';

export const PRACTICE_KEY = 'overhand.practice.v1';
/** A control ignores presses this long after its card or receipt appears (repeat clicks, held pokes). */
export const QUIET_MS = 600;
/**
 * The recorded card that opens Recorded mode: the Chief's weekly agent-hours question. Decision 1 is
 * an account-membership check recorded 123 ms earlier and is asked straight after this one.
 */
export const OPENING_DECISION = '2';

/**
 * What really happened that day, for the three questions where the recording holds it (task #654's
 * table): the owner's own recorded answer (Q4, Q9) or the recorded outcome (Q8), then what the agent
 * did next. Every quoted string is a verbatim substring of the cited line of
 * src/recordings/real_autoflow_day.jsonl (tests/entry-recording.test.mjs checks it and the name scan).
 * Every other question keeps the practice-only receipt.
 */
export interface ThatDay { label: string; said: string; saidLine: number; then: string; next: string; nextLine: number }
export const THAT_DAY: Record<string, ThatDay> = {
  '4': { label: 'On the real day the answer was', said: 'accepted and joined', saidLine: 177,
    then: 'Then the agent', next: 'Registered (Q4 answered); data downloading to data/raw.', nextLine: 201 },
  '8': { label: 'That day', said: 'Data landed in data/raw', saidLine: 414,
    then: 'Then the agent queued', next: 'T2 Data card on the real files', nextLine: 422 },
  '9': { label: 'On the real day the answer was', said: 'competition is joined, operator is the account, solo team, final submission pick comes later', saidLine: 197,
    then: 'Then the agent', next: 'Entry done; parked until ~Nov 20 per the Chief; next: build the instrument and candidate pool', nextLine: 597 },
};
/** The receipt's evidence: the practice line unchanged, then the recorded lines where they exist. */
export function receiptEvidence(decisionId: string): string {
  const t = THAT_DAY[decisionId];
  const practice = 'Practice only. No agent was contacted.';
  return t ? [practice, `${t.label}: "${t.said}"`, `${t.then}: "${t.next}"`].join('\n') : practice;
}

export interface PracticeReceipt {
  receipt_id: string;
  decision_id: string;
  project: string;
  project_name: string;
  question: string;
  option_id: string;
  option_label: string;
}
export interface PracticeSave { intro: boolean; receipts: PracticeReceipt[]; held: string | null }

const isReceipt = (r: unknown): r is PracticeReceipt => !!r && typeof r === 'object' &&
  ['receipt_id', 'decision_id', 'project', 'project_name', 'question', 'option_id', 'option_label']
    .every(k => typeof (r as Record<string, unknown>)[k] === 'string');

export function readPractice(storage?: Pick<Storage, 'getItem'>): PracticeSave {
  try {
    const v = JSON.parse(storage?.getItem(PRACTICE_KEY) ?? 'null');
    if (v && typeof v.intro === 'boolean' && Array.isArray(v.receipts)) {
      return { intro: v.intro, receipts: v.receipts.filter(isReceipt), held: typeof v.held === 'string' ? v.held : null };
    }
  } catch { /* A denied or corrupt store starts fresh. */ }
  return { intro: false, receipts: [], held: null };
}
export function writePractice(storage: Pick<Storage, 'setItem'> | undefined, value: PracticeSave): void {
  try { storage?.setItem(PRACTICE_KEY, JSON.stringify(value)); } catch { /* Keep the session usable. */ }
}

type Card = Pick<AgentEvent, 'decision_id' | 'title' | 'options' | 'project' | 'project_name'>;

export class PracticeFlow {
  introDone: boolean;
  receipts: PracticeReceipt[];
  held: PracticeReceipt | null;
  selected: string | null = null;
  private card = '';
  private shownAt = -Infinity;

  constructor(save: PracticeSave) {
    this.introDone = save.intro;
    this.receipts = save.receipts;
    this.held = save.receipts.find(r => r.decision_id === save.held) ?? null;
  }

  get save(): PracticeSave {
    return { intro: this.introDone, receipts: this.receipts, held: this.held?.decision_id ?? null };
  }

  /** Keep only receipts that still name a bundled question and option; they own the answer map. */
  reconcile(events: AgentEvent[], answers: Record<string, string>): void {
    const asks = new Map(events.filter(e => e.kind === 'decision' && e.decision_id).map(e => [e.decision_id!, e]));
    this.receipts = this.receipts.filter(r => {
      const ask = asks.get(r.decision_id);
      return ask?.title === r.question && ask.options?.some(o => o.id === r.option_id && o.label === r.option_label);
    });
    for (const r of this.receipts) answers[r.decision_id] = r.option_id;
    this.held = this.receipts.find(r => r.decision_id === this.held?.decision_id) ?? null;
  }

  /** The front card: a new one starts unselected and quiet. */
  show(key: string, now: number): void {
    if (key === this.card) return;
    this.card = key;
    this.selected = null;
    this.shownAt = now;
  }
  armed(now: number): boolean { return now - this.shownAt >= QUIET_MS; }

  select(d: Card, optionId: string, now: number): boolean {
    if (this.held || !this.armed(now) || !d.options?.some(o => o.id === optionId)) return false;
    this.selected = optionId;
    return true;
  }

  /** Saves the selected option once; returns null for no selection, a quiet window or a repeat. */
  confirm(d: Card, now: number): PracticeReceipt | null {
    const option = d.options?.find(o => o.id === this.selected);
    if (this.held || !option || !d.decision_id || !this.armed(now) || this.receipts.some(r => r.decision_id === d.decision_id)) return null;
    const receipt: PracticeReceipt = {
      receipt_id: `practice-${d.decision_id}-${option.id}`,
      decision_id: d.decision_id,
      project: d.project ?? '',
      project_name: d.project_name ?? d.project ?? '',
      question: d.title ?? '',
      option_id: option.id,
      option_label: option.label,
    };
    this.receipts.push(receipt);
    this.held = receipt;
    this.selected = null;
    this.shownAt = now;
    return receipt;
  }

  /** Dismisses the held receipt; only a deliberate press after the quiet window counts. */
  next(now: number): boolean {
    if (!this.held || !this.armed(now)) return false;
    this.held = null;
    this.shownAt = now;
    return true;
  }
}
