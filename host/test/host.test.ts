// Offline suite: protocol fixtures, stream converters, pairing refusals, pinning, resume after a mid-run drop, cancel.
import { test, after } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, readdirSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { Host } from "../src/server.ts";
import { HeadsetClient, PinMismatch } from "../src/client.ts";
import { validate, checkCode, parsePairUri, pairUri, TOKEN_TTL_MS } from "../src/protocol.ts";
import { ClaudeConverter, CodexConverter } from "../src/agents.ts";
import { lanAddress, resolveBind, tailnetAddress } from "../src/main.ts";
import { runFakeHeadset } from "./fake_headset.ts";

const FIX = new URL("./fixtures/", import.meta.url);
const STREAM = (n: string) => join(new URL("./fixtures/streams/", import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, "$1"), n);
const BIND = process.env.HOST_TEST_BIND ?? "127.0.0.1";
const temps: string[] = [];
const tmp = () => { const d = mkdtempSync(join(tmpdir(), "fhost-")); temps.push(d); return d; };
after(() => { for (const d of temps) try { rmSync(d, { recursive: true, force: true }); } catch {} });

let clock = Date.now();
async function host(extra: object = {}) {
  const d = tmp();
  const h = new Host({ bind: BIND, port: 0, dir: join(d, "cfg"), projectsRoot: join(d, "projects"), agent: "replay",
    replayFile: STREAM("claude_hello.jsonl"), now: () => clock, log: () => {}, ...extra });
  const a = await h.start();
  return { h, port: a.port, root: join(d, "projects") };
}
/** Devices paired over the link (the command line's own local device is always there). */
const paired = (h: Host) => h.devices.devices.filter(d => d.device_id !== "d_local").length;
const closed = (c: HeadsetClient) => new Promise<number>(r => c.closed ? r(c.closeCode) : c.ws.once("close", code => r(code)));

// ---- contract ---------------------------------------------------------------------------------------------
test("every protocol fixture frame validates in its direction", () => {
  const files = readdirSync(new URL("protocol/", FIX)).filter(f => f.endsWith(".json"));
  for (const need of ["pair", "resume", "create_project", "prompt", "event", "decision_asked", "decision_answered", "error"]) assert.ok(files.includes(`${need}.json`), need);
  for (const f of files) {
    const fx = JSON.parse(readFileSync(new URL(`protocol/${f}`, FIX), "utf8"));
    for (const { dir, ...frame } of fx.frames) assert.equal(validate(frame, dir), null, `${f}: ${JSON.stringify(frame)}`);
  }
  const pair = JSON.parse(readFileSync(new URL("protocol/pair.json", FIX), "utf8"));
  const q = parsePairUri(pair.qr);
  assert.equal(q.fp, pair.check_inputs.fp);
  assert.equal(checkCode(q.fp, q.t), pair.check_inputs.check_for_token);
  assert.equal(checkCode(pair.check_inputs.fp, pair.check_inputs.code), pair.check_inputs.check_for_code);
  assert.equal(pairUri(q), pair.qr);
});

test("validator rejects malformed frames", () => {
  assert.match(validate({ type: "pair_code", code: "12345", device_name: "x" }, "c2h")!, /6 digits/);
  assert.match(validate({ type: "resume", device_id: "d", device_secret: "s", last_seq: -1 }, "c2h")!, /last_seq/);
  assert.match(validate({ type: "nope" }, "c2h")!, /unknown type/);
  assert.match(validate({ type: "answer", req_id: "1", decision_id: "d", option_id: "allow", extra: 1 }, "c2h")!, /unknown field/);
});

// ---- converters on real recorded streams -------------------------------------------------------------------
const BRAND = /claude|anthropic|codex|openai|gpt|opus|sonnet/i;
function convert(file: string, Conv: any) {
  const cwd = "C:\\proj"; const out: any[] = []; const asked: string[] = [];
  const c = new Conv(cwd);
  for (const l of readFileSync(STREAM(file), "utf8").split("\n").filter(x => x.trim())) c.feed(l.split("{{CWD}}").join("C:\\\\proj"), (e: any) => out.push(e), (id: string) => asked.push(id));
  return { out, asked, ok: c.ok };
}
test("recorded agent stream A becomes started / file_written / command_run / question / message", () => {
  const { out, asked, ok } = convert("claude_hello.jsonl", ClaudeConverter);
  assert.equal(out[0].kind, "started");
  assert.deepEqual(out.filter(e => e.kind === "file_written").map(e => e.path), ["hello.js", "README.md"]);
  assert.ok(out.some(e => e.kind === "command_run" && e.command === "node hello.js"));
  assert.ok(out.some(e => e.kind === "command_run" && /^git add/.test(e.command)));
  assert.deepEqual(asked, ["perm-fixture-1"]);
  assert.equal(ok, true);
  for (const e of out) { assert.doesNotMatch(JSON.stringify(e), BRAND); assert.doesNotMatch(JSON.stringify(e), /[A-Z]:\\\\/); }
  const perm = convert("claude_permission_raw.jsonl", ClaudeConverter);
  assert.equal(perm.asked.length, 1, "live permission capture yields one question");
});
test("recorded agent stream B becomes started / file_written / command_run / message", () => {
  const { out, ok } = convert("codex_hello.jsonl", CodexConverter);
  assert.equal(out[0].kind, "started");
  assert.deepEqual(out.filter(e => e.kind === "file_written").map(e => e.path).sort(), ["README.md", "hello.js"]);
  assert.ok(out.some(e => e.kind === "command_run" && e.command === "node hello.js"), "shell wrapper stripped");
  assert.equal(ok, true);
  for (const e of out) assert.doesNotMatch(JSON.stringify(e), BRAND);
});

// ---- pairing and trust -------------------------------------------------------------------------------------
test("wrong code refused, socket closed, no device stored", async () => {
  const { h, port } = await host();
  const c = await new HeadsetClient(BIND, port, h.id.fp).open();
  const wrong = h.pairing.code.value === "000000" ? "000001" : "000000";
  c.send({ type: "pair_code", code: wrong, device_name: "x" });
  const e = await c.waitFor(f => f.type === "error");
  assert.equal(e.code, "bad_code"); assert.equal(await closed(c), 4401); assert.equal(paired(h), 0);
  await h.stop();
});
test("right code pairs and both sides compute the same check", async () => {
  const { h, port } = await host();
  const code = h.pairing.code.value;
  const c = await new HeadsetClient(BIND, port, h.id.fp).open();
  c.send({ type: "pair_code", code, device_name: "x" });
  const p = await c.waitFor(f => f.type === "paired");
  assert.equal(p.check, checkCode(h.id.fp, code));
  c.close(); await h.stop();
});
test("expired token refused", async () => {
  const { h, port } = await host();
  const tok = h.pairing.token.value;
  clock += TOKEN_TTL_MS + 1000;
  const c = await new HeadsetClient(BIND, port, h.id.fp).open();
  c.send({ type: "pair_token", token: tok, device_name: "x" });
  assert.equal((await c.waitFor(f => f.type === "error")).code, "token_expired");
  h.pairing.tick(); // the timer rotates it; still refused afterwards
  const c2 = await new HeadsetClient(BIND, port, h.id.fp).open();
  c2.send({ type: "pair_token", token: tok, device_name: "x" });
  assert.equal((await c2.waitFor(f => f.type === "error")).code, "token_expired");
  assert.equal(paired(h), 0);
  await h.stop();
});
test("reused token refused", async () => {
  const { h, port } = await host();
  const tok = h.pairing.token.value;
  const a = await new HeadsetClient(BIND, port, h.id.fp).open();
  a.send({ type: "pair_token", token: tok, device_name: "first" });
  assert.equal((await a.waitFor(f => f.type === "paired" || f.type === "error")).type, "paired");
  const b = await new HeadsetClient(BIND, port, h.id.fp).open();
  b.send({ type: "pair_token", token: tok, device_name: "second" });
  assert.equal((await b.waitFor(f => f.type === "error")).code, "token_used");
  assert.equal(paired(h), 1);
  a.close(); await h.stop();
});
test("wrong fingerprint refused by the client before any frame is sent", async () => {
  const { h, port } = await host();
  const bad = h.id.fp.replace(/^./, ch => (ch === "0" ? "1" : "0"));
  await assert.rejects(new HeadsetClient(BIND, port, bad).open(), PinMismatch);
  assert.equal(paired(h), 0);
  await h.stop();
});
test("guessing is rate limited and a code is burned after 5 wrong tries", async () => {
  const { h, port } = await host();
  const first = h.pairing.code.value;
  const codes: string[] = [];
  for (let i = 0; i < 11; i++) {
    const c = await new HeadsetClient(BIND, port, h.id.fp).open();
    const guess = String((Number(first) + 1 + i) % 1_000_000).padStart(6, "0");
    c.send({ type: "pair_code", code: guess, device_name: "x" });
    codes.push((await c.waitFor(f => f.type === "error")).code);
  }
  assert.notEqual(h.pairing.code.value, first, "code rotated after 5 wrong tries");
  assert.equal(codes.at(-1), "rate_limited");
  await h.stop();
});
test("frames other than pairing are refused before auth; bad device secret refused", async () => {
  const { h, port } = await host();
  const c = await new HeadsetClient(BIND, port, h.id.fp).open();
  c.send({ type: "create_project", req_id: "1", prompt: "x" });
  assert.equal((await c.waitFor(f => f.type === "error")).code, "unauthorized");
  const d = await new HeadsetClient(BIND, port, h.id.fp).open();
  d.send({ type: "resume", device_id: "d_nope", device_secret: "nope", last_seq: 0 });
  assert.equal((await d.waitFor(f => f.type === "error")).code, "unauthorized");
  await h.stop();
});

// ---- the whole flow, offline: QR text -> pair -> new project -> drop mid-run -> resume -> commit -------------
test("fake headset: resume after a mid-run drop loses no event, question survives, folder has working code + commit", async () => {
  const { h, port, root } = await host();
  const uri = pairUri({ h: BIND, p: port, fp: h.id.fp, t: h.pairing.token.value, n: "test" });
  const r = await runFakeHeadset({ uri, projectsRoot: root, dropAfter: 2 });
  assert.equal(r.drops, 1);
  assert.equal(r.resume_lost_events, 0, `missing ${r.seqs_missing}`);
  assert.deepEqual(r.seqs_duplicated, []);
  assert.equal(r.questions, 1); assert.equal(r.answered, 1);
  assert.equal(r.finished.ok, true);
  assert.ok(r.finished.commit, "commit sha reported");
  assert.ok(r.folder?.ok, JSON.stringify(r.folder));
  const log = h.log.events.filter(e => e.project_id === r.project_id).map(e => e.seq);
  assert.equal(r.events, log.length, "client processed every logged event of the project");
  await h.stop();
});

test("a question asked while the headset is away is delivered on resume and listed as open", async () => {
  const { h, port } = await host();
  const tok = h.pairing.token.value;
  const a = await new HeadsetClient(BIND, port, h.id.fp).open();
  a.send({ type: "pair_token", token: tok, device_name: "x" });
  const p = await a.waitFor(f => f.type === "paired"); a.close();
  const b = await new HeadsetClient(BIND, port, h.id.fp).open();
  b.send({ type: "resume", device_id: p.device_id, device_secret: p.device_secret, last_seq: 0 });
  await b.waitFor(f => f.type === "welcome");
  b.send({ type: "create_project", req_id: "c", prompt: "new project: hello" });
  await b.waitFor(f => f.type === "event" && f.kind === "started");
  b.drop();                                   // gone before the question is asked
  await new Promise(r => setTimeout(r, 1500)); // the replayed agent reaches its permission question meanwhile
  assert.equal(h.log.openDecisions().length, 1);
  const c = await new HeadsetClient(BIND, port, h.id.fp).open();
  c.send({ type: "resume", device_id: p.device_id, device_secret: p.device_secret, last_seq: b.lastSeq });
  const w = await c.waitFor(f => f.type === "welcome");
  assert.equal(w.open_decisions.length, 1);
  const q = await c.waitFor(f => f.type === "event" && f.kind === "question");
  assert.equal(q.decision_id, w.open_decisions[0]);
  c.send({ type: "answer", req_id: "z", decision_id: q.decision_id, option_id: "deny" });
  await c.waitFor(f => f.type === "event" && f.kind === "finished");
  c.send({ type: "answer", req_id: "z2", decision_id: q.decision_id, option_id: "allow" });
  assert.equal((await c.waitFor(f => f.type === "error")).code, "unknown_decision");
  c.close(); await h.stop();
});

test("cancel kills the agent process tree and reports a canceled finish", async () => {
  process.env.FAKE_AGENT_DELAY_MS = "2000";
  try {
    const { h, port } = await host();
    const a = await new HeadsetClient(BIND, port, h.id.fp).open();
    a.send({ type: "pair_token", token: h.pairing.token.value, device_name: "x" });
    const p = await a.waitFor(f => f.type === "paired"); a.close();
    const c = await new HeadsetClient(BIND, port, h.id.fp).open();
    c.send({ type: "resume", device_id: p.device_id, device_secret: p.device_secret, last_seq: 0 });
    await c.waitFor(f => f.type === "welcome");
    c.send({ type: "create_project", req_id: "c", prompt: "new project: slow" });
    const ok = await c.waitFor(f => f.type === "ok");
    const pid = h.runs.get(ok.task_id)!.child.pid!;
    c.send({ type: "cancel", req_id: "k", project_id: ok.project_id });
    const fin = await c.waitFor(f => f.type === "event" && f.kind === "finished", 15000);
    assert.equal(fin.ok, false); assert.equal(fin.reason, "canceled");
    assert.throws(() => process.kill(pid, 0), "agent process is gone");
    c.close(); await h.stop();
  } finally { delete process.env.FAKE_AGENT_DELAY_MS; }
});

// #1000: the build rehearsal lost a run (r07) to a 300 s cap while the agent was writing its last answer. The cap is
// per agent run and set by taskTimeoutMs; a run longer than the cap stops as "timeout", the same run under a longer cap finishes.
test("task timeout: a run over taskTimeoutMs stops as timeout, the same run under a longer cap does not", async () => {
  process.env.FAKE_AGENT_DELAY_MS = "100"; // ten build frames: at least one second, with no permission question
  try {
    for (const [cap, timesOut] of [[800, true], [30_000, false]] as const) {
      const { h, port } = await host({ taskTimeoutMs: cap, replayFile: STREAM("codex_build.jsonl") });
      const a = await new HeadsetClient(BIND, port, h.id.fp).open();
      a.send({ type: "pair_token", token: h.pairing.token.value, device_name: "x" });
      const p = await a.waitFor(f => f.type === "paired"); a.close();
      const c = await new HeadsetClient(BIND, port, h.id.fp).open();
      c.send({ type: "resume", device_id: p.device_id, device_secret: p.device_secret, last_seq: 0 });
      await c.waitFor(f => f.type === "welcome");
      c.send({ type: "create_project", req_id: "c", prompt: "new project: slow" });
      await c.waitFor(f => f.type === "ok");
      const end = await c.waitFor(f => f.type === "event" && f.kind === "finished", 20000);
      if (timesOut) { assert.equal(end.kind, "finished"); assert.equal(end.reason, "timeout"); assert.equal(end.title, "Stopped: took too long"); }
      else { assert.notEqual(end.reason, "timeout", `cap ${cap} ms must not stop the recorded build`); assert.equal(end.ok, true); }
      c.close(); await h.stop();
    }
  } finally { delete process.env.FAKE_AGENT_DELAY_MS; }
});

test("host picks a LAN address (non-loopback) when one exists", () => {
  const a = lanAddress();
  if (a) assert.doesNotMatch(a, /^127\./);
});

test("--bind tailnet picks the private-network address; a typed name or address is used as given", () => {
  const t = tailnetAddress();
  if (t) assert.match(t, /^100\.(6[4-9]|[7-9]\d|1[01]\d|12[0-7])\./);
  assert.equal(resolveBind("tailnet"), t);
  assert.equal(resolveBind("my-computer"), "my-computer");
  assert.equal(resolveBind(undefined), lanAddress());
});
