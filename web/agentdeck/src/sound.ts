/**
 * Sound cues (docs/design.md s5; files from task #56, assets/audio/README.md).
 * Each cue plays as a three.js PositionalAudio parented to the source panel, so it is
 * spatialised at the panel in XR. Per-agent pitch offset (design s3): +0 / +3 / +7 semitones.
 * IWSDK's AudioSource/AudioUtils.createOneShot was not used: createOneShot ignores its
 * `position` option in 1.1.0-rc.2 and AudioSource has no pitch control.
 */
import { AudioListener, AudioLoader, Object3D, PositionalAudio } from '@iwsdk/core';
import { deck } from './deck.js';
import { CUES, type Cue } from './cues.js';

const DETUNE: Record<string, number> = { researcher: 0, builder: 300, reviewer: 700 };
/** README advice: equal RMS makes the short cues loud; step-done fires often. */
const GAIN: Partial<Record<Cue, number>> = { 'step-done': 0.5 };
const MIN_GAP_MS = 120;
/** One cue of these kinds per window across all sources: two questions arriving together chime once. */
const SHARED_GAP_MS: Partial<Record<Cue, number>> = { 'step-done': 900, ask: 900 };

export class Sounds {
  private listener = new AudioListener();
  private buffers = new Map<Cue, AudioBuffer>();
  private voices = new Map<string, PositionalAudio>();
  private lastAt = new Map<string, number>();
  private pending = new Map<string, { cue: Cue; agentId: string }>();
  private lastCueAt = new Map<Cue, number>();

  constructor(
    camera: Object3D,
    private anchorFor: (agentId: string) => Object3D | null,
  ) {
    camera.add(this.listener);
    const loader = new AudioLoader();
    for (const c of CUES) {
      loader.load(
        `${import.meta.env.BASE_URL}audio/${c}.wav`,
        (b) => this.buffers.set(c, b),
        undefined,
        () => console.warn(`[overhand] sound ${c} failed to load`),
      );
    }
    // Browsers start the context suspended; any press or XR entry resumes it.
    const resume = () => void this.listener.context.resume();
    window.addEventListener('pointerdown', resume);
    window.addEventListener('keydown', resume);
  }

  resume(): void {
    void this.listener.context.resume();
  }

  /** Initial fixture events can precede station construction and audio decoding. */
  flush(): void {
    if (!this.pending.size) return;
    const pending = [...this.pending.values()];
    this.pending.clear();
    for (const { cue, agentId } of pending) this.play(cue, agentId);
  }

  play(cue: Cue, agentId: string): void {
    if (deck.muted) return;
    const now = performance.now();
    if (now - (this.lastCueAt.get(cue) ?? -1e9) < (SHARED_GAP_MS[cue] ?? 0)) return;
    const k = `${agentId}:${cue}`;
    if (now - (this.lastAt.get(k) ?? -1e9) < MIN_GAP_MS) return;
    const buf = this.buffers.get(cue);
    const anchor = this.anchorFor(agentId);
    if (!buf || !anchor) {
      if (cue === 'ask' || cue === 'approve' || cue === 'payoff') this.pending.set(k, { cue, agentId });
      return;
    }
    this.lastAt.set(k, now);
    this.lastCueAt.set(cue, now);
    let v = this.voices.get(k);
    if (!v) {
      v = new PositionalAudio(this.listener);
      v.setRefDistance(0.5);
      v.setBuffer(buf);
      v.setDetune(DETUNE[agentId] ?? 0);
      v.setVolume(GAIN[cue] ?? 1);
      anchor.add(v);
      this.voices.set(k, v);
    }
    if (v.isPlaying) v.stop();
    v.play();
    // step-done fires on every step; keep the log readable.
    if (cue !== 'step-done') deck.info(`sound ${cue} @${agentId} (${this.listener.context.state})`);
  }
}
