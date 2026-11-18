// Reference headset side of confirm-on-computer pairing (what hostlink/HostLink.kt does), for the host tests and the relay.
import { generateKeyPairSync } from "node:crypto";
import { HeadsetClient } from "../src/client.ts";
import { commitment, digits, newNonce } from "../src/pairing_confirm.ts";

export const devicePub = () => generateKeyPairSync("ec", { namedCurve: "P-256" }).publicKey.export({ format: "der", type: "spki" }).toString("base64url");

export interface ConfirmResult {
  /** Digits this headset shows: from the fingerprint it actually saw (the pin it enforced). */
  digits?: string; commitOk?: boolean; c?: string; hostNonce?: string; deviceNonce?: string;
  end?: any; // paired / pair_refused / error, or undefined if the socket closed first
}

/** Runs the exchange up to the digits; `result` resolves with the host's final answer. */
export async function startConfirm(c: HeadsetClient, opts: { name?: string; pub?: string; deviceNonce?: string } = {}) {
  const pub = opts.pub ?? devicePub();
  const r: ConfirmResult = {};
  c.send({ type: "pair_request", device_pub: pub, device_name: opts.name ?? "Headset" });
  const first = await c.waitFor(f => ["pair_commit", "pair_refused", "error"].includes(f.type));
  if (first.type !== "pair_commit") { r.end = first; return { r, pub, result: Promise.resolve(r) }; }
  r.c = first.c;
  r.deviceNonce = opts.deviceNonce ?? newNonce();
  c.send({ type: "pair_nonce", nonce: r.deviceNonce });
  const reveal = await c.waitFor(f => ["pair_reveal", "pair_refused", "error"].includes(f.type));
  if (reveal.type !== "pair_reveal") { r.end = reveal; return { r, pub, result: Promise.resolve(r) }; }
  r.hostNonce = reveal.nonce;
  r.commitOk = commitment(c.fp, pub, reveal.nonce) === r.c;
  r.digits = digits(c.fp, pub, reveal.nonce, r.deviceNonce);
  const result = c.waitFor(f => ["paired", "pair_refused", "error"].includes(f.type), 200_000)
    .then(f => { r.end = f; return r; }, () => r);
  return { r, pub, result };
}
