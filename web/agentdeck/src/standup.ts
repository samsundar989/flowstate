/** Persisted morning-session state. Storage may be unavailable in private browsing. */
export interface StandupSave {
  position: number;
  answers: Record<string, string>;
  tutorial: boolean;
}
export const STANDUP_KEY = 'overhand.standup.v1';
export function readStandup(storage?: Pick<Storage, 'getItem'>): StandupSave {
  try {
    const value = JSON.parse(storage?.getItem(STANDUP_KEY) ?? 'null');
    if (value && Number.isFinite(value.position) && value.position >= 0 &&
      typeof value.answers === 'object' && value.answers && typeof value.tutorial === 'boolean') return value;
  } catch { /* A denied or corrupt store starts a fresh stand-up. */ }
  return { position: 0, answers: {}, tutorial: false };
}
export function writeStandup(storage: Pick<Storage, 'setItem'> | undefined, value: StandupSave): void {
  try { storage?.setItem(STANDUP_KEY, JSON.stringify(value)); } catch { /* Keep the session usable. */ }
}

export type StandupStage = 'intro' | 'summary' | 'station' | 'scrub' | 'thumbs' | 'replay' | 'complete';
export class StandupSession {
  stage: StandupStage = 'summary';
  stageAt: number;
  minutes: number | null = null;
  readonly startedAt: number;
  tutorialDone: boolean;
  constructor(startedAt: number, tutorialDone: boolean) {
    this.startedAt = startedAt; this.tutorialDone = tutorialDone; this.stageAt = startedAt;
  }
  advance(now: number): void {
    const next: Record<string, StandupStage> = {
      intro: 'replay', summary: this.tutorialDone ? 'replay' : 'station', station: 'scrub', scrub: 'thumbs', thumbs: 'replay',
    };
    if (this.stage === 'thumbs') this.tutorialDone = true;
    this.stage = next[this.stage] ?? this.stage;
    this.stageAt = now;
  }
  tick(now: number): boolean {
    const timeout = this.stage === 'summary' ? 5000 : 18000;
    if (['summary', 'station', 'scrub', 'thumbs'].includes(this.stage) && now - this.stageAt >= timeout) {
      this.advance(now);
      return true;
    }
    return false;
  }
  complete(now: number): number {
    this.stage = 'complete';
    return this.minutes ??= (now - this.startedAt) / 60000;
  }
}

/** Text on the decision card for one guided beat (rubric E1.3: <= 25 words per card). */
export interface CardCopy { header: string; question: string; recommend: string; default: string; evidence: string; hint: string }
export const MAX_TUTORIAL_WORDS = 25;
export const countWords = (c: CardCopy): number =>
  Object.values(c).join(' ').split(/\s+/u).filter(Boolean).length;
const clip = (s: string, n: number): string => {
  const w = s.split(/\s+/u).filter(Boolean);
  return w.length <= n ? w.join(' ') : `${w.slice(0, n).join(' ')}...`;
};
/** "7 projects, 15 decisions" from a portfolio fixture (the Chief is not a project). */
export function portfolioLine(events: { project?: string; kind?: string }[]): string {
  const projects = new Set(events.map(e => e.project).filter(id => id && id !== 'chief')).size;
  return `${projects} projects, ${events.filter(e => e.kind === 'decision').length} decisions`;
}
/** First Recorded view: who it helps and that input is local practice, before any gesture. */
export function introCard(): CardCopy {
  return {
    header: 'RECORDED PRACTICE',
    question: 'Replay real coding-agent work and practice its decisions.',
    recommend: 'For anyone running several agents: see which one needs you.',
    default: '',
    evidence: 'Choose an option, then confirm. Your answer stays on this device. No agent is contacted.',
    hint: 'BEGIN SHOWS THE FIRST DECISION',
  };
}
/** Guided-beat copy; `station` is the name of the station the station beat promotes. */
export function tutorialCard(stage: StandupStage, chief: string, portfolio: string, station: string): CardCopy | null {
  const blank = { default: '', evidence: '' };
  if (stage === 'summary') return { header: 'STAND-UP / CHIEF', question: clip(chief, 11), recommend: portfolio, ...blank, hint: 'Replay the day to begin.' };
  if (stage === 'station') return { header: 'GUIDED 1 OF 3', question: `Poke ${clip(station, 3)} to bring it closer`, recommend: 'It is lit, straight ahead.', ...blank, hint: 'SKIP on the tray skips a beat.' };
  if (stage === 'scrub') return { header: 'GUIDED 2 OF 3', question: 'Pinch the blue knob and drag along the timeline', recommend: 'Let go to jump to that moment.', ...blank, hint: 'Follow the pale hand.' };
  if (stage === 'thumbs') return { header: 'GUIDED 3 OF 3', question: 'Ready for your morning stand-up?', recommend: '', ...blank, hint: 'Hold thumbs-up half a second, or poke it.' };
  return null;
}
