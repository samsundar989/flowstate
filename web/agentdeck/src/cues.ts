/**
 * Which sound cue a runtime event plays (cue files: public/audio, from task #56).
 * Pure (no DOM / IWSDK): shared by src/sound.ts and scripts/replay-smoke.mjs.
 */
import type { AgentEvent } from './replay.js';

export const CUES = ['ask', 'approve', 'stop', 'assign', 'step-done', 'fail', 'payoff'] as const;
export type Cue = (typeof CUES)[number];

const APPROVE_IDS = new Set(['approve', 'continue']);

/** question asked, approved, rejected/stopped, task assigned, step completed, failure. */
export function cueFor(e: AgentEvent): Cue | null {
  switch (e.kind) {
    case 'decision':
      return e.status === 'INPUT_REQUIRED' ? 'ask' : null;
    case 'status':
      if (e.decision_id) return null; // schema 1.1 decision already emitted the ask
      if (e.status === 'INPUT_REQUIRED') return 'ask';
      if (e.status === 'FAILED') return 'fail';
      return null;
    case 'input_response':
      if (e.decision_id) return 'approve';
      return APPROVE_IDS.has(e.answer ?? '') ? 'approve' : 'stop';
    case 'task_assigned':
      return 'assign';
    case 'step_end':
      if (e.status === 'COMPLETED') return 'step-done';
      if (e.status === 'FAILED') return 'fail';
      return null;
    default:
      return null;
  }
}
