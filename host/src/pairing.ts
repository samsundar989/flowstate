// One-time QR tokens and 6-digit codes: expiry, single use, wrong-code burn, per-address rate limit.
import { createHash, randomBytes, randomInt, timingSafeEqual } from "node:crypto";
import { CODE_TTL_MS, MAX_CODE_TRIES, MAX_FAILS_PER_MIN, TOKEN_TTL_MS, type ErrorCode } from "./protocol.ts";

interface Secret { value: string; expires: number; used: boolean }
const sha = (s: string) => createHash("sha256").update(s).digest();
const same = (a: string, b: string) => timingSafeEqual(sha(a), sha(b));

export class Pairing {
  token!: Secret; code!: Secret;
  private tries = 0;
  private fails = new Map<string, number[]>();
  private lockedUntil = new Map<string, number>();
  private old: Secret[] = [];
  onRotate: () => void = () => {};
  private now: () => number;
  constructor(now: () => number = Date.now) { this.now = now; this.rotate(); }

  rotate() {
    if (this.token) this.old.push(this.token, this.code);
    this.old = this.old.slice(-8);
    const t = this.now();
    this.token = { value: randomBytes(16).toString("base64url"), expires: t + TOKEN_TTL_MS, used: false };
    this.code = { value: String(randomInt(0, 1_000_000)).padStart(6, "0"), expires: t + CODE_TTL_MS, used: false };
    this.tries = 0;
    this.onRotate();
  }
  /** Called on a timer: replace an expired or used pair so the terminal always shows a live one. */
  tick() { const t = this.now(); if (this.token.used || this.code.used || t >= this.token.expires || t >= this.code.expires) this.rotate(); }

  private limited(from: string): boolean {
    const t = this.now();
    if ((this.lockedUntil.get(from) ?? 0) > t) return true;
    return false;
  }
  private fail(from: string) {
    const t = this.now(), list = (this.fails.get(from) ?? []).filter(x => t - x < 60_000);
    list.push(t); this.fails.set(from, list);
    if (list.length >= MAX_FAILS_PER_MIN) this.lockedUntil.set(from, t + 60_000);
  }

  useToken(value: string, from: string): ErrorCode | null {
    if (this.limited(from)) return "rate_limited";
    const t = this.now();
    if (same(value, this.token.value)) {
      if (this.token.used) { this.fail(from); return "token_used"; }
      if (t >= this.token.expires) { this.fail(from); return "token_expired"; }
      this.token.used = true; return null;
    }
    const prior = this.old.find(s => same(value, s.value));
    this.fail(from);
    if (prior) return prior.used ? "token_used" : "token_expired";
    return "bad_token";
  }

  useCode(value: string, from: string): ErrorCode | null {
    if (this.limited(from)) return "rate_limited";
    const t = this.now();
    if (same(value, this.code.value) && !this.code.used) {
      if (t >= this.code.expires) { this.fail(from); return "code_expired"; }
      this.code.used = true; return null;
    }
    this.fail(from);
    if (++this.tries >= MAX_CODE_TRIES) this.rotate(); // burn the code after 5 wrong guesses
    return "bad_code";
  }
}
