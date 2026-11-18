// #972 build from nothing: a new project's first task plans (parts + one design question) before any file exists,
// builds after the answer, and every commit on main is announced as a `preview` whose bytes the headset fetches with
// `get_file` over the pinned link. A follow-up `prompt` on the same project skips the plan and previews again.
import { test, after } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import { Host, planParts } from "../src/server.ts";
import { HeadsetClient } from "../src/client.ts";
import { validate, CHUNK_B64, MAX_FILE_BYTES } from "../src/protocol.ts";
import { parseFinal, FINAL_SCHEMA } from "../src/agents.ts";

const STREAMS = join(new URL("./fixtures/streams/", import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, "$1"));
const BIND = process.env.HOST_TEST_BIND ?? "127.0.0.1";
const temps: string[] = [];
after(() => { for (const d of temps) try { rmSync(d, { recursive: true, force: true }); } catch {} });
const BRAND = /claude|anthropic|codex|openai|gpt|opus|sonnet/i;
const sha = (b: Buffer) => createHash("sha256").update(b).digest("hex");
const gitIn = (cwd: string, ...a: string[]) => spawnSync("git", a, { cwd, encoding: "buffer" });

async function start() {
  const d = mkdtempSync(join(tmpdir(), "fbuild-")); temps.push(d);
  const h = new Host({ bind: BIND, port: 0, dir: join(d, "cfg"), projectsRoot: join(d, "projects"), agent: "replay",
    replayFile: join(STREAMS, "codex_web_plan.jsonl"), log: () => {} });
  const { port } = await h.start();
  const a = await new HeadsetClient(BIND, port, h.id.fp).open();
  a.send({ type: "pair_token", token: h.pairing.token.value, device_name: "test" });
  const creds = await a.waitFor(f => f.type === "paired"); a.close();
  const c = await new HeadsetClient(BIND, port, h.id.fp).open();
  c.send({ type: "resume", device_id: creds.device_id, device_secret: creds.device_secret, last_seq: 0 });
  await c.waitFor(f => f.type === "welcome");
  return { d, h, c };
}
let reqN = 0;
/** Fetch a whole file chunk by chunk, as the preview window does. */
async function fetchFile(c: HeadsetClient, project_id: string, commit: string, path: string) {
  const parts: Buffer[] = []; let offset = 0, total = -1, digest = "", chunks = 0;
  do {
    const req_id = `g${++reqN}`;
    c.send({ type: "get_file", req_id, project_id, commit, path, offset });
    const f = await c.waitFor(x => x.req_id === req_id && (x.type === "file_chunk" || x.type === "error"));
    if (f.type === "error") return { error: f.code as string };
    assert.ok(f.data.length <= CHUNK_B64); assert.equal(f.offset, offset); assert.equal(f.path, path);
    const b = Buffer.from(f.data, "base64"); parts.push(b); offset += b.length; total = f.total; digest = f.sha256; chunks++;
  } while (offset < total);
  const bytes = Buffer.concat(parts);
  assert.equal(sha(bytes), digest, "the bytes hash to the sha the host announced");
  return { bytes, sha256: digest, chunks };
}
async function refusal(c: HeadsetClient, frame: any) {
  const req_id = `r${++reqN}`;
  c.ws.send(JSON.stringify({ type: "get_file", req_id, offset: 0, ...frame }));   // raw: the host must refuse it on its own
  const f = await c.waitFor(x => x.req_id === req_id && (x.type === "file_chunk" || x.type === "error"));
  return f.type === "error" ? f.code : "served";
}
/** Run a whole new-project flow and return the ids, the preview and the project folder. */
async function buildOne(h: Host, c: HeadsetClient) {
  c.send({ type: "create_project", req_id: "c1", prompt: "new project: a tap counter with a big button and a reset" });
  const ok = await c.waitFor(f => f.type === "ok" && f.req_id === "c1");
  const q = await c.waitFor(f => f.type === "event" && f.kind === "question" && f.task_id === ok.task_id);
  c.send({ type: "answer", req_id: "a1", decision_id: q.decision_id, option_id: "o1" });
  const fin = await c.waitFor(f => f.type === "event" && f.kind === "finished" && f.task_id === ok.task_id, 30000);
  const pv = await c.waitFor(f => f.type === "event" && f.kind === "preview" && f.task_id === ok.task_id);
  return { ok, q, fin, pv, pdir: h.projectDir(ok.project_id) };
}

test("plan phase: parts and one design question before any file, build after the answer, preview matches git, follow-up previews again", async () => {
  const { d, h, c } = await start();
  try {
    const { ok, q, fin, pv, pdir } = await buildOne(h, c);
    const ev = h.log.events.filter(e => e.task_id === ok.task_id);
    // plan before question, and before any file was written
    const plan = ev.find(e => e.kind === "plan")!;
    assert.ok(plan, "a plan event");
    assert.ok(plan.seq < q.seq, "plan comes before the question");
    assert.equal(plan.parts!.length, 4);
    assert.deepEqual(plan.parts!.map(p => p.id), ["layout", "look", "counter", "reset"]);
    for (const p of plan.parts!) { assert.ok(p.name.length <= 24); assert.doesNotMatch(p.name, BRAND); }
    assert.ok(!ev.some(e => e.kind === "file_written" && e.seq < q.seq), "no file before the answer");
    assert.equal(q.options.length, 3); assert.equal(q.title, "Which color theme should it use?");
    // file_written carries the part its path belongs to (first part listing it)
    const writes = ev.filter(e => e.kind === "file_written");
    assert.deepEqual(writes.map(e => [e.path, e.part_id]), [["index.html", "layout"], ["style.css", "look"], ["app.js", "counter"]]);
    // finished, then preview, then done; the preview names main's full commit and the bytes of index.html there
    assert.equal(fin.ok, true); assert.equal(fin.merged, true);
    assert.ok(pv.commit.startsWith(fin.commit)); assert.match(pv.commit, /^[0-9a-f]{40}$/);
    assert.equal(pv.path, "index.html");
    const head = gitIn(pdir, "rev-parse", "main").stdout.toString().trim();
    assert.equal(pv.commit, head);
    const shown = gitIn(pdir, "show", `${pv.commit}:index.html`).stdout as Buffer;
    assert.equal(pv.sha256, sha(shown)); assert.equal(pv.bytes, shown.length);
    const done = ev.find(e => e.kind === "task_state" && e.state === "done")!;
    assert.ok(fin.seq < pv.seq && pv.seq < done.seq, "finished, preview, done");
    // the preview window fetches all three files over the link
    const got = await fetchFile(c, ok.project_id, pv.commit, "index.html");
    assert.ok(!("error" in got)); assert.equal((got as any).sha256, pv.sha256);
    for (const f of ["style.css", "app.js"]) {
      const r = await fetchFile(c, ok.project_id, pv.commit, f) as any;
      assert.equal(r.sha256, sha(gitIn(pdir, "show", `${pv.commit}:${f}`).stdout as Buffer));
    }
    // the prompts the agent received: plan first (web shape pinned), then the answer
    const raws = readdirSync(join(d, "cfg", "raw")).sort().map(f => readFileSync(join(d, "cfg", "raw", f), "utf8")).join("\n");
    assert.match(raws, /index\.html.{0,40}at most 3 files/);
    assert.match(raws, /no CDN/); assert.match(raws, /1280x800/); assert.match(raws, /tap or click only/);
    assert.match(raws, /\\"parts\\":\[<3 to 6/);

    // ---- follow-up: same project, new task, no plan, no question, a new preview ----
    c.send({ type: "prompt", req_id: "f1", project_id: ok.project_id, text: "add a minus one button [replay:codex_web_followup.jsonl]" });
    const ok2 = await c.waitFor(f => f.type === "ok" && f.req_id === "f1");
    assert.equal(ok2.project_id, ok.project_id); assert.notEqual(ok2.task_id, ok.task_id);
    const fin2 = await c.waitFor(f => f.type === "event" && f.kind === "finished" && f.task_id === ok2.task_id, 30000);
    const pv2 = await c.waitFor(f => f.type === "event" && f.kind === "preview" && f.task_id === ok2.task_id);
    const ev2 = h.log.events.filter(e => e.task_id === ok2.task_id);
    assert.ok(!ev2.some(e => e.kind === "plan" || e.kind === "question"), "a follow-up skips the plan phase");
    assert.equal(fin2.ok, true); assert.equal(fin2.merged, true);
    assert.notEqual(pv2.commit, pv.commit);
    assert.equal(gitIn(pdir, "rev-parse", "main").stdout.toString().trim(), pv2.commit);
    assert.equal(gitIn(pdir, "merge-base", "--is-ancestor", pv.commit, pv2.commit).status, 0, "the follow-up builds on the first commit");
    const html2 = gitIn(pdir, "show", `${pv2.commit}:index.html`).stdout as Buffer;
    assert.equal(pv2.sha256, sha(html2)); assert.match(html2.toString(), /Minus one/);
    assert.deepEqual(ev2.filter(e => e.kind === "file_written").map(e => e.part_id), ["layout", "counter"], "follow-up writes are tagged with the plan's parts");
    assert.match(raws + readdirSync(join(d, "cfg", "raw")).map(f => readFileSync(join(d, "cfg", "raw", f), "utf8")).join("\n"), /Do not ask a question; make the change/);
    // the older commit is still fetchable (both are on main)
    assert.equal((await fetchFile(c, ok.project_id, pv.commit, "index.html") as any).sha256, pv.sha256);

    for (const e of h.log.events) { assert.equal(validate(e, "h2c"), null, JSON.stringify(e)); assert.doesNotMatch(e.title, BRAND); }
    if (process.env.RECORD_BUILD_FLOW) writeFileSync(process.env.RECORD_BUILD_FLOW, JSON.stringify({ events: h.log.events, pv, pv2 }, null, 1));
  } finally { c.close(); await h.stop(); }
});

test("get_file refuses traversal, untracked files, commits off main, unknown commits, big files and bad offsets; chunks big files", async () => {
  const { h, c } = await start();
  try {
    const { ok, pv, pdir } = await buildOne(h, c);
    const P = ok.project_id, C = pv.commit;
    // path traversal and odd names never reach git
    for (const path of ["../outside.txt", "a/../index.html", "./index.html", "/etc/passwd", "C:/Windows/win.ini", "..\\index.html", "index.html/", ""])
      assert.equal(await refusal(c, { project_id: P, commit: C, path }), "bad_path", path);
    // a real file just outside the project folder is still refused
    writeFileSync(join(pdir, "..", "outside.txt"), "secret");
    assert.equal(await refusal(c, { project_id: P, commit: C, path: "../outside.txt" }), "bad_path");
    // .git internals, untracked files in the folder, folders: not tracked at the commit
    writeFileSync(join(pdir, "untracked.txt"), "not committed");
    for (const path of [".git/config", "untracked.txt", ".worktrees", "missing.js"])
      assert.equal(await refusal(c, { project_id: P, commit: C, path }), "not_found", path);
    // a file that exists at main's head but not at the asked commit
    const first = gitIn(pdir, "rev-list", "--max-parents=0", "main").stdout.toString().trim();
    assert.equal(await refusal(c, { project_id: P, commit: first, path: "index.html" }), "not_found");
    // a commit that is not on main (a task branch the host never merged), and one that does not exist
    gitIn(pdir, "checkout", "-q", "-b", "side");
    writeFileSync(join(pdir, "side.html"), "<p>side</p>"); gitIn(pdir, "add", "side.html");
    gitIn(pdir, "-c", "user.name=t", "-c", "user.email=t@t.invalid", "commit", "-q", "-m", "side");
    const side = gitIn(pdir, "rev-parse", "HEAD").stdout.toString().trim();
    gitIn(pdir, "checkout", "-q", "main");
    assert.equal(await refusal(c, { project_id: P, commit: side, path: "side.html" }), "not_found");
    assert.equal(await refusal(c, { project_id: P, commit: "deadbeefdeadbeef", path: "index.html" }), "not_found");
    // not hex (could be an option or a ref name): refused by the validator
    for (const commit of ["main", "--output=x", "HEAD~1", "abc"]) assert.equal(await refusal(c, { project_id: P, commit, path: "index.html" }), "bad_request", commit);
    assert.equal(await refusal(c, { project_id: "p_nope", commit: C, path: "index.html" }), "unknown_project");
    // offset past the end
    assert.equal(await refusal(c, { project_id: P, commit: C, path: "index.html", offset: pv.bytes }), "bad_request");
    assert.equal(await refusal(c, { project_id: P, commit: C, path: "index.html", offset: pv.bytes + 10 }), "bad_request");
    // size: a 20 KB file comes in several chunks; one over 512 KB is refused
    const mid = Buffer.alloc(20_000, "a"), big = Buffer.alloc(MAX_FILE_BYTES + 1, "b");
    writeFileSync(join(pdir, "mid.txt"), mid); writeFileSync(join(pdir, "big.txt"), big);
    gitIn(pdir, "add", "mid.txt", "big.txt"); gitIn(pdir, "-c", "user.name=t", "-c", "user.email=t@t.invalid", "commit", "-q", "-m", "sizes");
    const C2 = gitIn(pdir, "rev-parse", "HEAD").stdout.toString().trim();
    const m = await fetchFile(c, P, C2, "mid.txt") as any;
    assert.equal(m.sha256, sha(mid)); assert.equal(m.chunks, Math.ceil(20_000 / 4500));
    assert.equal(await refusal(c, { project_id: P, commit: C2, path: "big.txt" }), "too_large");
    // the connection is still usable after every refusal
    assert.equal((await fetchFile(c, P, C, "index.html") as any).sha256, pv.sha256);
  } finally { c.close(); await h.stop(); }
});

test("plan parts are cleaned for the headset; plan, preview, get_file and file_chunk frames are validated", () => {
  const parts = planParts([
    { id: "Main Screen!", name: "The OpenAI-free main screen with a long name", files: ["./index.html", "/opt/flowstate\\a.js", "../up.js", "app.js"] },
    { id: "main-screen", name: "", files: ["style.css"] },
    { id: "", name: "Claude helper", files: [] },
  ], "C:\\work");
  assert.deepEqual(parts.map(p => p.id), ["main-screen", "p2", "p3"]);
  assert.ok(parts.every(p => p.name.length >= 1 && p.name.length <= 24));
  assert.doesNotMatch(parts.map(p => p.name).join(" "), BRAND);
  assert.deepEqual(parts[0].files, ["index.html", "app.js"]);
  assert.equal(parts[1].name, "Part 2");
  // the closing answer: parts parsed, and the enforced schema requires them
  const fin = parseFinal(JSON.stringify({ status: "question", summary: "s", question: "q?", options: ["A", "B"], parts: [{ id: "a", name: "A", files: ["index.html"] }] }))!;
  assert.deepEqual(fin.parts, [{ id: "a", name: "A", files: ["index.html"] }]);
  assert.deepEqual(parseFinal('{"status":"done","summary":"x","question":"","options":[]}')!.parts, []);
  assert.ok(FINAL_SCHEMA.required.includes("parts"));
  const ev = (o: object) => ({ type: "event", seq: 1, at: "t", project_id: "p", title: "x", ...o });
  const good = [{ id: "a", name: "A", files: ["index.html"] }];
  assert.equal(validate(ev({ kind: "plan", task_id: "t1", parts: good }), "h2c"), null);
  for (const bad of [[], Array(7).fill(good[0]), [{ id: "a", name: "x".repeat(25), files: [] }], [{ id: "a", name: "A", files: ["../x"] }],
    [good[0], good[0]], [{ id: "A B", name: "A", files: [] }], [{ ...good[0], extra: 1 }]])
    assert.notEqual(validate(ev({ kind: "plan", task_id: "t1", parts: bad }), "h2c"), null, JSON.stringify(bad));
  assert.notEqual(validate(ev({ kind: "plan", parts: good }), "h2c"), null, "plan needs task_id");
  const pv = { kind: "preview", commit: "a".repeat(40), path: "index.html", bytes: 10, sha256: "b".repeat(64) };
  assert.equal(validate(ev(pv), "h2c"), null);
  assert.notEqual(validate(ev({ ...pv, commit: "abc1234" }), "h2c"), null);
  assert.notEqual(validate(ev({ ...pv, sha256: undefined }), "h2c"), null);
  const chunk = { type: "file_chunk", req_id: "g", path: "index.html", sha256: "b".repeat(64), offset: 0, total: 3, data: "YWJj" };
  assert.equal(validate(chunk, "h2c"), null);
  assert.notEqual(validate({ ...chunk, data: "A".repeat(CHUNK_B64 + 4) }, "h2c"), null);
  assert.notEqual(validate({ ...chunk, total: MAX_FILE_BYTES + 1 }, "h2c"), null);
  assert.notEqual(validate({ ...chunk, data: "<script>" }, "h2c"), null);
  assert.equal(validate({ type: "get_file", req_id: "g", project_id: "p", commit: "abcdef1", path: "index.html", offset: 0 }, "c2h"), null);
  assert.notEqual(validate({ type: "get_file", req_id: "g", project_id: "p", commit: "abcdef1", path: "index.html" }, "c2h"), null, "offset required");
});
