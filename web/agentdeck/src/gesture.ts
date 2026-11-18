/**
 * Thumbs-up / thumbs-down answers for INPUT_REQUIRED (docs/design.md s3 rows 6-7).
 *
 * IWSDK 1.1.0-rc.2 exposes hand joints (WebXR XRHand) but no pose recogniser, so the pose
 * is hand-rolled from joint positions: four fingers curled + thumb extended, and the thumb
 * pointing world-up (thumbs-up) or world-down (thumbs-down).
 * No DOM or IWSDK imports: the smoke test feeds synthetic joint sets under node.
 */
import type { AgentOption, Question } from './replay.js';

export type Thumb = 'up' | 'down';
export type Vec3 = [number, number, number];
/** WebXR joint name -> position (metres, a +Y-up reference space). */
export type Joints = Record<string, Vec3>;

export const HOLD_MS = 500;
/** No answer in the first second after the card appears. */
export const ARM_MS = 1000;

const FINGERS = ['index-finger', 'middle-finger', 'ring-finger', 'pinky-finger'];
const sub = (a: Vec3, b: Vec3): Vec3 => [a[0] - b[0], a[1] - b[1], a[2] - b[2]];
const len = (a: Vec3) => Math.hypot(a[0], a[1], a[2]);
const dist = (a: Vec3, b: Vec3) => len(sub(a, b));

/** Classify one hand. Returns null for anything that is not a clear thumbs-up/down. */
export function classifyThumb(j: Joints): Thumb | null {
  const wrist = j['wrist'];
  if (!wrist) return null;
  for (const f of FINGERS) {
    const tip = j[`${f}-tip`];
    const knuckle = j[`${f}-phalanx-proximal`];
    if (!tip || !knuckle) return null;
    // Curled: the tip has folded back to (or inside) the knuckle's distance from the wrist.
    if (dist(tip, wrist) > dist(knuckle, wrist) * 1.05) return null;
  }
  const tTip = j['thumb-tip'];
  const tBase = j['thumb-phalanx-proximal'];
  const iKnuckle = j['index-finger-phalanx-proximal'];
  if (!tTip || !tBase || !iKnuckle) return null;
  // Extended: the thumb tip stands clear of the index knuckle (a fist wraps it over).
  // Calibrated on the emulator hand: fist 5.5 cm, thumbs-up 10.4 cm.
  if (dist(tTip, iKnuckle) < 0.08) return null;
  const d = sub(tTip, tBase);
  const up = d[1] / (len(d) || 1);
  if (up > 0.6) return 'up';
  if (up < -0.6) return 'down';
  return null;
}

/** The option a thumbs-up (approve) or thumbs-down (stop / reject) answers. */
export function optionFor(q: Question, kind: Thumb): AgentOption | undefined {
  const g = kind === 'up' ? 'thumbs_up' : 'thumbs_down';
  const tagged = q.options.find((o) => o.gesture === g);
  if (tagged) return tagged;
  if (kind === 'up') return q.options[0];
  const up = optionFor(q, 'up');
  return q.options.find((o) => o !== up);
}

/**
 * Hold-to-answer gate. A pose counts only if it was formed after the card armed
 * (ARM_MS after it appeared) and held HOLD_MS; each question is answered at most once.
 */
export class ThumbGate {
  private pose: Thumb | null = null;
  private since = 0;
  private answered = new Set<string>();

  /** Progress 0..1 of the current hold, for the hint line. */
  progress = 0;

  update(
    pose: Thumb | null,
    nowMs: number,
    target: { key: string; shownAtMs: number } | null,
  ): Thumb | null {
    if (pose !== this.pose) {
      this.pose = pose;
      this.since = nowMs;
    }
    this.progress = 0;
    if (!pose || !target || this.answered.has(target.key) || !Number.isFinite(this.since)) return null;
    const armedAt = target.shownAtMs + ARM_MS;
    if (this.since < armedAt) return null; // formed before the card armed: release first
    this.progress = Math.min(1, (nowMs - this.since) / HOLD_MS);
    if (nowMs - this.since < HOLD_MS) return null;
    this.answered.add(target.key);
    this.progress = 0;
    return pose;
  }

  /**
   * Hand a fired key back when the thumb had nothing to answer (a malformed recommendation with no
   * marked default), so the card stays open to the other pose; the held pose must be released first.
   */
  release(key: string): void {
    this.answered.delete(key);
    this.since = Number.POSITIVE_INFINITY;
  }
}

export const questionKey = (agentId: string, q: Question) =>
  `${agentId}:${q.step_id}:${q.askedAtReal}`;
