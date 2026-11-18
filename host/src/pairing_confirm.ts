// Pairing by confirming six digits on the computer (numeric comparison with a commitment; docs/host/PROTOCOL.md 2d).
//
//   headset                                   host
//   pair_request{device_pub, device_name} ->
//                                          <- pair_commit{c}        c  = H("flowstate-commit|1|" fp | device_pub | Na)
//   pair_nonce{nonce: Nb}                 ->
//                                          <- pair_reveal{nonce: Na}  (only after Nb arrived)
//                                          <- pair_pending{digits_hint: "none"}
//   both:  digits = be32(H("flowstate-digits|1|" fp | device_pub | Na | Nb)[0..4]) mod 10^6
//   the person at the computer types y      <- paired{...}   |   n / 120 s   <- pair_refused{reason}
//
// fp is the host's own certificate fingerprint here and the fingerprint the headset actually saw there, so a relay with
// its own certificate yields different digits on the two screens. The host commits to Na before it sees Nb, and the
// headset sends Nb before it sees Na: neither side (nor anyone in between) can choose a nonce after seeing the other's,
// so making the six digits match by trying keys or nonces succeeds with probability 10^-6 per attempt, and attempts are
// limited to one pending request at a time, 120 s each, 3 refusals per source per minute.
import { createHash, createPublicKey, randomBytes } from "node:crypto";
import { createInterface } from "node:readline";
import type { Device, DeviceStore, Identity } from "./config.ts";
import { checkCode } from "./protocol.ts";

export const CONFIRM_TTL_MS = 120_000;
export const MAX_REFUSALS_PER_MIN = 3;
export const REFUSAL_REASONS = ["declined", "expired", "busy", "rate_limited", "out_of_order"] as const;
export type RefusalReason = typeof REFUSAL_REASONS[number];

const sha = (s: string) => createHash("sha256").update(s, "utf8").digest();
export const newNonce = () => randomBytes(16).toString("base64url");
export const commitment = (fp: string, devicePub: string, hostNonce: string) => sha(`flowstate-commit|1|${fp}|${devicePub}|${hostNonce}`).toString("hex");
export function digits(fp: string, devicePub: string, hostNonce: string, deviceNonce: string): string {
  const d = sha(`flowstate-digits|1|${fp}|${devicePub}|${hostNonce}|${deviceNonce}`);
  return String(d.readUInt32BE(0) % 1_000_000).padStart(6, "0");
}
export const spaced = (d: string) => `${d.slice(0, 3)} ${d.slice(3)}`;
/** A device public key: base64url SPKI DER of an EC or Ed25519 key (the headset makes a P-256 pair). */
export function validDevicePub(s: string): boolean {
  if (!/^[A-Za-z0-9_-]{40,400}$/.test(s)) return false;
  try { createPublicKey({ key: Buffer.from(s, "base64url"), format: "der", type: "spki" }); return true; } catch { return false; }
}

/** What the confirm flow needs from one connection. */
export interface ConfirmConn { send(f: object): void; close(code: number, reason: string): void; admit(d: Device): void }
interface Pending {
  conn: ConfirmConn; from: string; name: string; devicePub: string; hostNonce: string; c: string;
  deviceNonce?: string; digits?: string; started: number;
}
/** Host parts used, read lazily (the host loads its certificate in start()). */
export interface ConfirmHost { id: Identity; devices: DeviceStore; o: { now?: () => number; log?: (s: string) => void } }

export class PairConfirm {
  pending?: Pending;
  private refusals = new Map<string, number[]>();
  private timer?: ReturnType<typeof setInterval>;
  /** Fires when the computer must ask its person (terminal line, tests). */
  onAsk: (p: { name: string; from: string; digits: string }) => void = () => {};
  ttlMs = CONFIRM_TTL_MS;
  private host: ConfirmHost;
  constructor(host: ConfirmHost) { this.host = host; }
  private now() { return (this.host.o.now ?? Date.now)(); }
  private say(s: string) { (this.host.o.log ?? (x => console.log(x)))(s); }

  private refuse(conn: ConfirmConn, from: string, reason: RefusalReason, count = true) {
    if (count) { const t = this.now(); this.refusals.set(from, [...(this.refusals.get(from) ?? []).filter(x => t - x < 60_000), t]); }
    conn.send({ type: "pair_refused", reason });
    conn.close(4401, `pair ${reason}`);
  }
  private limited(from: string) { const t = this.now(); return (this.refusals.get(from) ?? []).filter(x => t - x < 60_000).length >= MAX_REFUSALS_PER_MIN; }
  private end() { this.pending = undefined; if (this.timer) clearInterval(this.timer); this.timer = undefined; }

  /** A pair_request or pair_nonce from an unauthenticated connection. */
  frame(conn: ConfirmConn, f: any, from: string) {
    from = from.replace(/^::ffff:/, "");
    const p = this.pending;
    if (f.type === "pair_request") {
      if (p?.conn === conn) return this.cancel("out_of_order");
      if (this.limited(from)) return this.refuse(conn, from, "rate_limited", false);
      if (p) return this.refuse(conn, from, "busy");
      if (!validDevicePub(f.device_pub)) { conn.send({ type: "error", code: "bad_request", message: "That request was not understood." }); return conn.close(4400, "bad device_pub"); }
      const hostNonce = newNonce();
      const c = commitment(this.host.id.fp, f.device_pub, hostNonce);
      this.pending = { conn, from, name: String(f.device_name).replace(/[^\p{L}\p{N} ._'-]/gu, "").slice(0, 40) || "Headset",
        devicePub: f.device_pub, hostNonce, c, started: this.now() };
      this.timer = setInterval(() => this.tick(), 250); this.timer.unref?.();
      return conn.send({ type: "pair_commit", c });
    }
    // pair_nonce: only on the connection that holds the pending request, only once, only after the commit.
    if (!p || p.conn !== conn || p.deviceNonce) { if (p?.conn === conn) return this.cancel("out_of_order"); return this.refuse(conn, from, "out_of_order"); }
    p.deviceNonce = f.nonce;
    p.digits = digits(this.host.id.fp, p.devicePub, p.hostNonce, p.deviceNonce!);
    conn.send({ type: "pair_reveal", nonce: p.hostNonce });
    conn.send({ type: "pair_pending", digits_hint: "none" });
    this.say(`\nPair "${p.name}" (${p.from})? Code ${spaced(p.digits)}. Type y to allow`);
    this.onAsk({ name: p.name, from: p.from, digits: p.digits });
  }

  /** The person at the computer answered. Nothing is issued before a yes. Returns false when nothing was waiting. */
  decide(yes: boolean): boolean {
    const p = this.pending;
    if (!p?.digits) return false;
    this.end();
    if (!yes) { this.say(`Refused "${p.name}" (${p.from}).`); this.refuse(p.conn, p.from, "declined"); return true; }
    const device_secret = randomBytes(32).toString("base64url");
    const d: Device = { device_id: "d_" + randomBytes(6).toString("hex"), secret_sha256: createHash("sha256").update(device_secret).digest("hex"),
      name: p.name, paired_at: new Date().toISOString(), from: p.from };
    this.host.devices.add(d);
    p.conn.admit(d);
    p.conn.send({ type: "paired", device_id: d.device_id, device_secret, host_name: this.host.id.name, check: checkCode(this.host.id.fp, p.digits) });
    this.say(`PAIRED device "${d.name}" from ${p.from} (code ${spaced(p.digits)}).`);
    return true;
  }

  /** The pending request's connection closed, or it broke the order. */
  closed(conn: ConfirmConn | object) { if (this.pending && this.pending.conn === conn) { this.say(`Pairing request from ${this.pending.from} withdrawn.`); this.end(); } }
  private cancel(reason: RefusalReason) { const p = this.pending!; this.end(); this.refuse(p.conn, p.from, reason); }
  tick() {
    const p = this.pending;
    if (p && this.now() - p.started >= this.ttlMs) { this.say(`Pairing request from ${p.from} expired.`); this.cancel("expired"); }
  }
}

/** Terminal: a line "y"/"yes" allows the waiting request, anything else refuses it. */
export function attachTerminal(c: PairConfirm, input: NodeJS.ReadableStream = process.stdin) {
  const rl = createInterface({ input });
  rl.on("line", l => { if (c.pending?.digits) c.decide(/^\s*y(es)?\s*$/i.test(l)); });
  return rl;
}
