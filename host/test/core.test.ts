// Job runner suite (offline, replayed agent streams): the task state machine, a question asked and answered by
// resuming the same agent session, a question waiting through a disconnect and a host restart, two tasks in one
// project, cancel while the agent writes, and a host process killed mid-task then restarted.
import { test, after } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, readdirSync, rmSync, existsSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { spawn, spawnSync } from "node:child_process";
import { Host } from "../src/server.ts";
import { HeadsetClient } from "../src/client.ts";
import { HostState, canMove, TASK_STATES, type TaskState } from "../src/state.ts";
import { validate } from "../src/protocol.ts";
import { connectLocal, statusText } from "../src/cli.ts";

const STREAMS = join(new URL("./fixtures/streams/", import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, "$1"));
const BIND = process.env.HOST_TEST_BIND ?? "127.0.0.1";
const temps: string[] = [];
const tmp = () => { const d = mkdtempSync(join(tmpdir(), "fcore-")); temps.push(d); return d; };
after(() => { if (!process.env.CORE_KEEP) for (const d of temps) try { rmSync(d, { recursive: true, force: true }); } catch {} });
const sleep = (ms: number) => new Promise(r => setTimeout(r, ms));
const BRAND = /claude|anthropic|codex|openai|gpt|opus|sonnet/i;

async function host(d: string, replay = "codex_build.jsonl", extra: object = {}) {
  const h = new Host({ bind: BIND, port: 0, dir: join(d, "cfg"), projectsRoot: join(d, "projects"), agent: "replay",
    replayFile: join(STREAMS, replay), log: process.env.CORE_DEBUG ? console.log : () => {}, ...extra });
  const a = await h.start();
  return { h, port: a.port };
}
/** A paired headset session (pairs by QR token on first use, then resumes like the real client). */
async function headset(h: Host, port: number, creds?: any, lastSeq = 0) {
  if (!creds) {
    const a = await new HeadsetClient(BIND, port, h.id.fp).open();
    a.send({ type: "pair_token", token: h.pairing.token.value, device_name: "test" });
    creds = await a.waitFor(f => f.type === "paired"); a.close();
  }
  const c = await new HeadsetClient(BIND, port, h.id.fp).open();
  c.lastSeq = lastSeq;
  c.send({ type: "resume", device_id: creds.device_id, device_secret: creds.device_secret, last_seq: lastSeq });
  const welcome = await c.waitFor(f => f.type === "welcome");
  return { c, creds, welcome };
}
const stateEv = (task: string, st: string) => (f: any) => f.type === "event" && f.kind === "task_state" && f.task_id === task && f.state === st;
const gitLog = (dir: string) => spawnSync("git", ["log", "--format=%h %ct %s", "main"], { cwd: dir, encoding: "utf8" }).stdout.trim().split("\n");

// ---- the state machine --------------------------------------------------------------------------------------
test("state machine: every transition is either allowed by the table or refused", () => {
  const allowed: Record<TaskState, TaskState[]> = {
    queued: ["running", "cancelled"], running: ["needs_answer", "done", "failed", "cancelled", "queued"],
    needs_answer: ["running", "cancelled", "failed", "queued"], done: [], failed: [], cancelled: [],
  };
  let n = 0;
  for (const from of TASK_STATES) for (const to of TASK_STATES) { assert.equal(canMove(from, to), allowed[from].includes(to), `${from} -> ${to}`); n++; }
  assert.equal(n, 36);
  for (const to of TASK_STATES) assert.equal(canMove(undefined, to), to === "queued");
  // the fold refuses an illegal sequence, so a corrupted log is noticed instead of silently believed
  const ev = (seq: number, state: string) => ({ type: "event", seq, at: "", project_id: "p", kind: "task_state", task_id: "t1", state, title: "x" } as any);
  assert.throws(() => HostState.from([ev(1, "queued"), ev(2, "done")]), /illegal transition/);
  const s = HostState.from([{ type: "event", seq: 1, at: "", project_id: "p", kind: "project_created", title: "x", name: "p" } as any, ev(2, "queued"), ev(3, "running")]);
  assert.equal(s.projects.get("p")!.state, "active");
  assert.equal(s.active("p").length, 1);
});

// ---- question -> answer -> resumed session -> commit -------------------------------------------------------
test("agent asks (closing answer), headset answers, the same session resumes, the commit lands on main after the answer", async () => {
  const d = tmp(); const { h, port } = await host(d, "codex_ask.jsonl");
  const { c } = await headset(h, port);
  c.send({ type: "create_project", req_id: "c1", prompt: "new project: a hello-world CLI that prints the time; ask me which language before writing" });
  const ok = await c.waitFor(f => f.type === "ok" && f.req_id === "c1");
  assert.match(ok.task_id, /^t\d+$/);
  const q = await c.waitFor(f => f.type === "event" && f.kind === "question");
  assert.equal(q.task_id, ok.task_id);
  assert.deepEqual(q.options.map((o: any) => o.label), ["Python", "JavaScript (Node)", "Go"]);
  assert.equal(q.allow_text, true);
  await c.waitFor(stateEv(ok.task_id, "needs_answer"));
  assert.deepEqual([...h.state.decisions.keys()], [q.decision_id]);
  assert.equal(h.runs.size, 0, "no agent process is held open while the question waits");
  const tAnswer = Math.floor(Date.now() / 1000);
  c.send({ type: "answer", req_id: "a1", decision_id: q.decision_id, option_id: "o1" });
  await c.waitFor(f => f.type === "ok" && f.req_id === "a1");
  const ans = await c.waitFor(f => f.type === "event" && f.kind === "answered" && f.decision_id === q.decision_id);
  const resumed = await c.waitFor(f => f.type === "event" && f.kind === "started" && f.task_id === ok.task_id && f.seq > ans.seq);
  assert.equal(resumed.title, "Agent resumed");
  const fin = await c.waitFor(f => f.type === "event" && f.kind === "finished" && f.task_id === ok.task_id);
  assert.equal(fin.ok, true); assert.equal(fin.merged, true); assert.ok(fin.commit);
  await c.waitFor(stateEv(ok.task_id, "done"));
  const pdir = h.projectDir(ok.project_id);
  const log = gitLog(pdir);
  assert.equal(log[0].split(" ")[0], fin.commit, "main's head is the commit reported in the result event");
  assert.ok(Number(log[0].split(" ")[1]) >= tAnswer, "the commit was made after the answer");
  assert.ok(existsSync(join(pdir, "hello.py")));
  assert.ok(!existsSync(join(pdir, ".worktrees", ok.task_id)), "worktree removed after a merge");
  // the resumed process got the same session id and the person's answer
  const raws = readdirSync(join(d, "cfg", "raw")).map(f => readFileSync(join(d, "cfg", "raw", f), "utf8")).join("\n");
  assert.match(raws, /"resume":"0b5e2c8a-1111-7000-8000-00000000a5c1"/);
  assert.match(raws, /answered your question.{0,80}: Python/);
  for (const e of h.log.events) { assert.equal(validate(e, "h2c"), null, JSON.stringify(e)); assert.doesNotMatch(e.title, BRAND); }
  c.close(); await h.stop();
});

// ---- question while disconnected, across a host restart ----------------------------------------------------
test("question asked while no headset is connected waits, survives a host restart, and is delivered on resume", async () => {
  const d = tmp(); let { h, port } = await host(d, "codex_ask.jsonl");
  const first = await headset(h, port);
  first.c.send({ type: "create_project", req_id: "c1", prompt: "ask me which language first" });
  const ok = await first.c.waitFor(f => f.type === "ok");
  await first.c.waitFor(f => f.type === "event" && f.kind === "started");
  const last = first.c.lastSeq; first.c.drop();             // the headset goes away before the question
  for (let i = 0; i < 100 && h.state.tasks.get(ok.task_id)!.state !== "needs_answer"; i++) await sleep(50);
  assert.equal(h.state.tasks.get(ok.task_id)!.state, "needs_answer");
  await h.stop();                                            // and the host restarts while it waits
  ({ h, port } = await host(d, "codex_ask.jsonl"));
  assert.equal(h.state.tasks.get(ok.task_id)!.state, "needs_answer", "still waiting after the restart");
  const back = await headset(h, port, first.creds, last);
  assert.equal(back.welcome.open_decisions.length, 1);
  assert.equal(back.welcome.projects[0].tasks[0].state, "needs_answer");
  const q = await back.c.waitFor(f => f.type === "event" && f.kind === "question");
  assert.equal(q.decision_id, back.welcome.open_decisions[0]);
  back.c.send({ type: "answer", req_id: "a", decision_id: q.decision_id, text: "Python please" });
  await back.c.waitFor(stateEv(ok.task_id, "done"), 20000);
  assert.ok(existsSync(join(h.projectDir(ok.project_id), "hello.py")));
  back.c.send({ type: "answer", req_id: "a2", decision_id: q.decision_id, option_id: "o1" });
  assert.equal((await back.c.waitFor(f => f.type === "error")).code, "unknown_decision", "a question is answered once");
  back.c.close(); await h.stop();
});

// ---- two tasks in one project --------------------------------------------------------------------------------
test("two tasks in one project run one after the other, each in its own worktree, both merged into main", async () => {
  const d = tmp(); const { h, port } = await host(d);
  const { c } = await headset(h, port);
  c.send({ type: "create_project", req_id: "c1", prompt: "new project: clock" });
  const a = await c.waitFor(f => f.type === "ok" && f.req_id === "c1");
  c.send({ type: "prompt", req_id: "p2", project_id: a.project_id, text: "add a python version [replay:codex_ask.resume.jsonl]" });
  const b = await c.waitFor(f => f.type === "ok" && f.req_id === "p2");
  assert.notEqual(a.task_id, b.task_id);
  assert.equal(h.state.tasks.get(b.task_id)!.state, "queued", "second task waits: one agent per project");
  const doneA = await c.waitFor(stateEv(a.task_id, "done"), 20000);
  const runB = await c.waitFor(stateEv(b.task_id, "running"), 20000);
  assert.ok(runB.seq > doneA.seq, "the second task started only after the first finished");
  await c.waitFor(stateEv(b.task_id, "done"), 20000);
  const pdir = h.projectDir(a.project_id);
  for (const f of ["main.js", "clock.js", "hello.py"]) assert.ok(existsSync(join(pdir, f)), f);
  const log = gitLog(pdir);
  assert.equal(log.length, 3, log.join("\n")); // project created + one per task
  assert.match(statusText(join(d, "cfg")), new RegExp(`${b.task_id}\\s+done`));
  c.close(); await h.stop();
});

// ---- cancel while the agent is writing ---------------------------------------------------------------------
test("cancel while the agent writes: process tree killed, task cancelled, main untouched, worktree kept and named", async () => {
  process.env.FAKE_AGENT_DELAY_MS = "500";
  try {
    const d = tmp(); const { h, port } = await host(d);
    const { c } = await headset(h, port);
    c.send({ type: "create_project", req_id: "c1", prompt: "new project: slow clock" });
    const ok = await c.waitFor(f => f.type === "ok");
    await c.waitFor(f => f.type === "event" && f.kind === "file_written", 15000);
    const pid = h.runs.get(ok.task_id)!.child.pid!;
    c.send({ type: "cancel", req_id: "k", project_id: ok.project_id });
    const fin = await c.waitFor(f => f.type === "event" && f.kind === "finished", 15000);
    assert.equal(fin.ok, false); assert.equal(fin.reason, "canceled"); assert.equal(fin.worktree, `.worktrees/${ok.task_id}`);
    const st = await c.waitFor(stateEv(ok.task_id, "cancelled"));
    assert.equal(st.worktree, `.worktrees/${ok.task_id}`);
    assert.throws(() => process.kill(pid, 0), "agent process is gone");
    const pdir = h.projectDir(ok.project_id);
    assert.ok(existsSync(join(pdir, ".worktrees", ok.task_id, "clock.js")), "partial work kept in the worktree");
    assert.ok(!existsSync(join(pdir, "clock.js")), "nothing merged into main");
    assert.equal(gitLog(pdir).length, 1);
    c.send({ type: "cancel", req_id: "k2", project_id: ok.project_id, task_id: ok.task_id });
    assert.equal((await c.waitFor(f => f.type === "error")).code, "unknown_project", "a finished task cannot be cancelled again");
    c.close(); await h.stop();
  } finally { delete process.env.FAKE_AGENT_DELAY_MS; }
});

// ---- the host process is killed mid-task and started again ---------------------------------------------------
test("host process killed mid-task: restart rebuilds state from the log, kills the orphan agent, re-runs the task to a merged commit", async () => {
  const d = tmp(), cfg = join(d, "cfg"), projects = join(d, "projects");
  const args = ["--experimental-strip-types", "--no-warnings", "src/main.ts", "--bind", BIND, "--port", "0", "--no-mdns",
    "--agent", "replay", "--replay", join(STREAMS, "codex_build.jsonl"), "--config", cfg, "--projects", projects];
  const proc = spawn(process.execPath, args, { env: { ...process.env, FAKE_AGENT_DELAY_MS: "600" }, stdio: "ignore" });
  for (let i = 0; i < 200 && !existsSync(join(cfg, "host.json")); i++) await sleep(50);
  // the command line's `new` path: the same frame the headset sends, over the same link
  const { c } = await connectLocal(cfg);
  c.send({ type: "create_project", req_id: "n1", prompt: "a clock", name: "clock" });
  const ok = await c.waitFor(f => f.type === "ok");
  await c.waitFor(f => f.type === "event" && f.kind === "file_written", 20000);
  const meta = JSON.parse(readFileSync(join(cfg, "tasks", `${ok.task_id}.json`), "utf8"));
  assert.ok(meta.pid, "agent pid recorded");
  c.close();
  proc.kill("SIGKILL");                                      // hard kill of the host only; its agent is orphaned
  await new Promise(r => proc.once("exit", r));
  let orphanAlive = true; try { process.kill(meta.pid, 0); } catch { orphanAlive = false; }
  const before = HostState.from(readFileSync(join(cfg, "events.jsonl"), "utf8").trim().split("\n").map(l => JSON.parse(l)));
  assert.equal(before.tasks.get(ok.task_id)!.state, "running", "the log says running when the host died");

  const h = new Host({ bind: BIND, port: 0, dir: cfg, projectsRoot: projects, agent: "replay", replayFile: join(STREAMS, "codex_build.jsonl"), log: () => {} });
  await h.start();
  if (orphanAlive) assert.throws(() => process.kill(meta.pid, 0), "orphaned agent killed on restart");
  const evs = h.log.events.filter(e => e.task_id === ok.task_id);
  assert.ok(evs.some(e => e.kind === "task_state" && e.state === "queued" && e.reason === "host_restarted"));
  for (let i = 0; i < 300 && h.state.tasks.get(ok.task_id)!.state !== "done"; i++) await sleep(50);
  assert.equal(h.state.tasks.get(ok.task_id)!.state, "done");
  const pdir = h.projectDir(ok.project_id);
  assert.ok(existsSync(join(pdir, "main.js")));
  assert.equal(gitLog(pdir).length, 2);
  assert.ok(h.log.events.some(e => e.task_id === ok.task_id && e.kind === "started" && e.title === "Agent resumed"), "resumed the recorded session");
  await h.stop();
  assert.ok(!existsSync(join(cfg, "host.json")));
});
