// #1035 "talk to an agent": a `say` frame reaches one running task's own agent session and no other, the agent's reply
// comes back as that task's events, and a task with no live run is refused (the headset then says the send failed).
import { test, after } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, rmSync, readFileSync, readdirSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { Host, talkPrompt } from "../src/server.ts";
import { validate } from "../src/protocol.ts";

const STREAMS = join(new URL("./fixtures/streams/", import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, "$1"));
const temps: string[] = [];
after(() => { for (const d of temps) try { rmSync(d, { recursive: true, force: true }); } catch {} });

test("say: the sentence reaches the targeted run's session only, its reply comes back, a finished task is refused", async () => {
  const d = mkdtempSync(join(tmpdir(), "ftalk-")); temps.push(d);
  const h = new Host({ bind: "127.0.0.1", port: 0, dir: join(d, "cfg"), projectsRoot: join(d, "projects"), agent: "replay",
    replayFile: join(STREAMS, "claude_slow.jsonl"), log: () => {}, maxRunning: 2, taskTimeoutMs: 20_000 } as any);
  await h.start();
  const events: any[] = [];
  h.log.follow(0, e => { events.push(e); });
  const pa = h.createProject("new project: a tap counter"), pb = h.createProject("new project: a timer");
  const ta = h.enqueue(pa, "new project: a tap counter", "replay"), tb = h.enqueue(pb, "new project: a timer", "replay");
  h.pump();
  const until = async (what: string, f: () => boolean, ms = 10_000) => { const end = Date.now() + ms; while (!f()) { assert.ok(Date.now() < end, what); await new Promise(r => setTimeout(r, 20)); } };
  await until("both started", () => [ta, tb].every(t => events.some(e => e.kind === "started" && e.task_id === t)));
  const handle = (f: any) => new Promise<any>(res => (h as any).handle(f, (code: string, req_id?: string) => res({ type: "error", code, req_id }), (x: any) => res(x)));
  const marker = "please check the reset button, code word kestrel";
  const frame = { type: "say", req_id: "say_1", project_id: pa, task_id: ta, text: marker };
  assert.equal(validate(frame, "c2h" as any) ?? null, null);
  const r = await handle(frame);
  assert.equal(r.type, "ok", JSON.stringify(r)); assert.equal(r.task_id, ta);
  const heard = events.find(e => e.kind === "answered" && e.req_id === "say_1");
  assert.ok(heard && heard.task_id === ta && heard.text === marker, "an answered event names the task and the words");
  await until("reply", () => events.some(e => e.kind === "message" && e.task_id === ta && e.seq > heard.seq && /I heard you/.test(e.detail ?? e.title)));
  assert.equal(events.some(e => e.kind === "message" && e.task_id === tb && /I heard you/.test(e.detail ?? e.title)), false, "the other agent did not reply");
  // Wrong pairings and unknowns are refused, never redirected.
  assert.equal((await handle({ ...frame, req_id: "say_2", project_id: pb })).code, "not_found");
  assert.equal((await handle({ ...frame, req_id: "say_3", project_id: "p_nope" })).code, "unknown_project");
  await until("both done", () => [ta, tb].every(t => events.some(e => e.kind === "task_state" && e.task_id === t && e.state === "done")), 15_000);
  assert.equal((await handle({ ...frame, req_id: "say_4" })).code, "not_found", "a finished task has no live session");
  await h.stop();
  // The agent processes' own input: the words reached task A's run and no other.
  const raws = readdirSync(join(d, "cfg", "raw")).map(f => readFileSync(join(d, "cfg", "raw", f), "utf8"));
  const runOf = (t: string) => events.find(e => e.kind === "started" && e.task_id === t).run_id;
  const rawOf = (t: string) => readFileSync(join(d, "cfg", "raw", readdirSync(join(d, "cfg", "raw")).find(f => f.startsWith(runOf(t)))!), "utf8");
  assert.equal(raws.length, 2);
  assert.ok(rawOf(ta).includes("code word kestrel"), "target session received it");
  assert.equal(rawOf(tb).includes("kestrel"), false, "other session did not");
  assert.equal((rawOf(ta).match(/INPUT \{"nudge"/g) ?? []).length, 1, "exactly one message");
});

test("say: validation and the prompt wrapper", () => {
  assert.match(validate({ type: "say", req_id: "a", project_id: "p", task_id: "t", text: " " }, "c2h" as any) ?? "", /say needs/);
  assert.match(validate({ type: "say", req_id: "a", project_id: "p", task_id: "t", text: "x".repeat(401) }, "c2h" as any) ?? "", /say needs/);
  assert.match(validate({ type: "say", req_id: "a", project_id: "p", text: "hi" }, "c2h" as any) ?? "", /task_id/);
  const p = talkPrompt("what are you on");
  assert.ok(p.includes('"what are you on"') && /continue your task/.test(p));
});
