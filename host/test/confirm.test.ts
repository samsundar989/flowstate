// Pairing by confirming six digits on the computer (PROTOCOL.md 2d): happy path, no, timeout, single pending, refusal
// limit, a client that withholds its nonce, three relays with their own certificate, and the typed-code path unchanged.
import { test, after } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, rmSync, writeFileSync, mkdirSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { Host } from "../src/server.ts";
import { HeadsetClient } from "../src/client.ts";
import { checkCode, validate } from "../src/protocol.ts";
import { commitment, digits } from "../src/pairing_confirm.ts";
import { startConfirm } from "./confirm_client.ts";
import { startRelay, type RelayMode } from "./mitm_relay.ts";

const BIND = process.env.HOST_TEST_BIND ?? "127.0.0.1";
const OUT = process.env.PAIR_CONFIRM_OUT; // runs/host/pair_confirm when run for the record
const temps: string[] = [];
after(() => { for (const d of temps) try { rmSync(d, { recursive: true, force: true }); } catch {} });
let clock = Date.now();
async function host() {
  const d = mkdtempSync(join(tmpdir(), "fconfirm-")); temps.push(d);
  const lines: string[] = [];
  const h = new Host({ bind: BIND, port: 0, dir: join(d, "cfg"), projectsRoot: join(d, "projects"), agent: "replay", now: () => clock, log: s => lines.push(s) });
  const asks: { name: string; from: string; digits: string }[] = [];
  h.confirm.onAsk = a => asks.push(a);
  const a = await h.start();
  return { h, port: a.port, asks, lines };
}
const open = (h: Host, port: number) => new HeadsetClient(BIND, port, h.id.fp).open();
const closed = (c: HeadsetClient) => new Promise<number>(r => c.closed ? r(c.closeCode) : c.ws.once("close", code => r(code)));
const until = async (cond: () => boolean, ms = 5000) => { const end = Date.now() + ms; while (!cond()) { if (Date.now() > end) throw new Error("timed out"); await new Promise(r => setTimeout(r, 10)); } };
const record = (name: string, o: object) => { if (OUT) { mkdirSync(OUT, { recursive: true }); writeFileSync(join(OUT, name), JSON.stringify(o, null, 1)); } };

/** Headsets paired over the network (#785 also keeps a local device for the command line, which is not one). */
const paired = (h: any) => h.devices.devices.filter((d: any) => d.device_id !== "d_local").length;

test("confirm frames: the fixture validates, and the commitment and digits match the fixture's inputs", () => {
  const fx = JSON.parse(readFileSync(new URL("./fixtures/protocol/pair_confirm.json", import.meta.url), "utf8"));
  for (const { dir, ...f } of fx.frames) assert.equal(validate(f, dir), null, JSON.stringify(f));
  const i = fx.inputs;
  assert.equal(commitment(i.fp, i.device_pub, i.host_nonce), fx.frames.find((f: any) => f.type === "pair_commit").c);
  assert.equal(digits(i.fp, i.device_pub, i.host_nonce, i.device_nonce), i.digits);
  assert.equal(checkCode(i.fp, i.digits), fx.frames.find((f: any) => f.type === "paired").check);
  assert.match(validate({ type: "pair_pending", digits_hint: "123456" }, "h2c")!, /never sends the digits/);
  assert.match(validate({ type: "pair_nonce", nonce: "short" }, "c2h")!, /nonce/);
  assert.match(validate({ type: "pair_refused", reason: "whatever" }, "h2c")!, /reason/);
});

test("happy path: same six digits on both screens; nothing issued before the y; the issued secret resumes", async () => {
  const { h, port, asks, lines } = await host();
  const c = await open(h, port);
  const { r, result } = await startConfirm(c, { name: "Quest 2" });
  await until(() => asks.length === 1);
  assert.equal(r.commitOk, true);
  assert.equal(r.digits, asks[0].digits, "both screens show the same digits");
  assert.ok(lines.some(l => l.includes(`Pair "Quest 2" (${BIND})? Code ${r.digits!.slice(0, 3)} ${r.digits!.slice(3)}. Type y to allow`)), lines.join("\n"));
  assert.equal(paired(h), 0, "no device before the y");
  assert.ok(!c.frames.some(f => f.type === "paired"));
  assert.equal(h.confirm.decide(true), true);
  const end = (await result).end;
  assert.equal(end.type, "paired"); assert.equal(end.check, checkCode(h.id.fp, r.digits!));
  assert.equal(paired(h), 1);
  c.close();
  const again = await open(h, port);
  again.send({ type: "resume", device_id: end.device_id, device_secret: end.device_secret, last_seq: 0 });
  assert.equal((await again.waitFor(f => f.type === "welcome")).type, "welcome");
  again.close(); await h.stop();
  record("happy.json", { digits: r.digits, host_digits: asks[0].digits, terminal: lines.find(l => l.includes("Type y")), paired: true });
});

test("the computer says no: refused, closed, nothing stored", async () => {
  const { h, port, asks } = await host();
  const c = await open(h, port);
  const { r, result } = await startConfirm(c);
  await until(() => asks.length === 1);
  h.confirm.decide(false);
  assert.deepEqual((await result).end, { type: "pair_refused", reason: "declined" });
  assert.equal(await closed(c), 4401); assert.equal(paired(h), 0); assert.ok(r.digits);
  await h.stop();
});

test("timeout: an unanswered request expires after 120 s and a late y issues nothing", async () => {
  const { h, port, asks } = await host();
  const c = await open(h, port);
  const { result } = await startConfirm(c);
  await until(() => asks.length === 1);
  clock += 119_000; h.confirm.tick();
  assert.ok(h.confirm.pending, "still waiting at 119 s");
  clock += 1_500; h.confirm.tick();
  assert.deepEqual((await result).end, { type: "pair_refused", reason: "expired" });
  assert.equal(h.confirm.decide(true), false, "a late y has nothing to allow");
  assert.equal(paired(h), 0);
  await h.stop();
});

test("one pending request at a time; 3 refusals a minute per source, then rate limited without asking", async () => {
  const { h, port, asks } = await host();
  const a = await open(h, port);
  await startConfirm(a);
  await until(() => asks.length === 1);
  const b = await open(h, port);
  const busy = await startConfirm(b);
  assert.deepEqual(busy.r.end, { type: "pair_refused", reason: "busy" });
  h.confirm.decide(false);                                        // refusal 2 from this source (busy was 1)
  const c = await open(h, port); const third = await startConfirm(c); await until(() => asks.length === 2);
  h.confirm.decide(false);                                        // refusal 3
  const d = await open(h, port); const fourth = await startConfirm(d);
  assert.deepEqual(fourth.r.end, { type: "pair_refused", reason: "rate_limited" });
  assert.equal(asks.length, 2, "the rate-limited request never reached the person");
  clock += 61_000;
  const e = await open(h, port); const later = await startConfirm(e);
  assert.ok(later.r.digits, "allowed again after a minute"); assert.ok(third.r.digits);
  e.close(); await h.stop();
});

test("a client that withholds its nonce never learns the host's nonce, and the computer is never asked", async () => {
  const { h, port, asks } = await host();
  const c = await open(h, port);
  c.send({ type: "pair_request", device_pub: (await import("./confirm_client.ts")).devicePub(), device_name: "Headset" });
  const commit = await c.waitFor(f => f.type === "pair_commit");
  await new Promise(r => setTimeout(r, 1500));
  assert.ok(!c.frames.some(f => f.type === "pair_reveal"), "no reveal without a nonce");
  assert.ok(!(await c.waitFor(f => f.type === "pair_reveal", 100).catch(() => null)));
  assert.equal(asks.length, 0);
  // A second request on the same socket (to get a fresh commitment) ends the attempt without a reveal.
  c.send({ type: "pair_request", device_pub: (await import("./confirm_client.ts")).devicePub(), device_name: "Headset" });
  assert.deepEqual(await c.waitFor(f => f.type === "pair_refused"), { type: "pair_refused", reason: "out_of_order" });
  assert.ok(!c.frames.some(f => f.type === "pair_reveal"));
  // A nonce with no request first gets nothing either.
  const d = await open(h, port);
  d.send({ type: "pair_nonce", nonce: "AAAAAAAAAAAAAAAAAAAAAA" });
  assert.deepEqual(await d.waitFor(f => f.type === "pair_refused"), { type: "pair_refused", reason: "out_of_order" });
  assert.ok(commit.c); assert.equal(paired(h), 0);
  // The withheld request still expires and the socket is closed (the 10 s auth timer is replaced by the 120 s expiry).
  clock += 121_000; h.confirm.tick();
  await h.stop();
});

async function mitm(mode: RelayMode) {
  const { h, port, asks } = await host();
  const relay = await startRelay({ bind: BIND, host: BIND, hostPort: port, hostFp: h.id.fp, mode });
  const c = await new HeadsetClient(BIND, relay.port, relay.fp).open();   // the headset pinned the fp it got from (spoofed) mDNS
  const { r, result } = await startConfirm(c);
  await until(() => asks.length === 1, 30_000);
  const out = { mode, headset_digits: r.digits, host_digits: asks[0].digits, headset_commitment_ok: r.commitOk, relay: relay.seen,
    differ: r.digits !== asks[0].digits };
  h.confirm.decide(false);   // the person compares, sees a different code, types n
  await result; c.close(); await relay.close(); await h.stop();
  record(`mitm_${mode}.json`, out);
  return out;
}
test("MITM, transparent relay with its own certificate: the commitment fails on the headset and the digits differ", async () => {
  const o = await mitm("transparent");
  assert.equal(o.headset_commitment_ok, false); assert.notEqual(o.headset_digits, o.host_digits);
});
test("MITM, active relay (own commitment, own key and nonces): the commitment checks out but the digits differ", async () => {
  const o = await mitm("active");
  assert.equal(o.headset_commitment_ok, true); assert.notEqual(o.headset_digits, o.host_digits);
});
test("MITM, grinding relay: forcing the host's digits onto the headset breaks its commitment", async () => {
  const o = await mitm("grind");
  assert.equal(o.relay.grindFound, true, "the relay found a matching nonce (expected about 10^6 tries)");
  assert.equal(o.headset_digits, o.host_digits, "so the six digits match...");
  assert.equal(o.headset_commitment_ok, false, "...but only by revealing a nonce it had not committed to, which the headset refuses");
});

test("typed code still pairs on a host that also offers confirm-on-computer", async () => {
  const { h, port } = await host();
  const code = h.pairing.code.value;
  const c = await open(h, port);
  c.send({ type: "pair_code", code, device_name: "x" });
  const p = await c.waitFor(f => f.type === "paired");
  assert.equal(p.check, checkCode(h.id.fp, code));
  c.close(); await h.stop();
});
