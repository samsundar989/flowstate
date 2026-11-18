// #1543: an existing repository added where it is. Add and list; the headset's list over the real protocol (the host's
// own test client); a task on it with a stub agent leaves the person's HEAD, branch, index and working files unchanged.
import { test, after } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, readdirSync, rmSync, existsSync, statSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, relative } from "node:path";
import { createHash } from "node:crypto";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import { Host } from "../src/server.ts";
import { HeadsetClient } from "../src/client.ts";
import { connectLocal, statusText } from "../src/cli.ts";

const STREAMS = fileURLToPath(new URL("./fixtures/streams/", import.meta.url));
const MAIN = fileURLToPath(new URL("../src/main.ts", import.meta.url));
const BIND = process.env.HOST_TEST_BIND ?? "127.0.0.1";
const BRAND = /claude|anthropic|codex|openai|gpt|opus|sonnet/i;
const temps: string[] = [];
const tmp = () => { const d = mkdtempSync(join(tmpdir(), "fadd-")); temps.push(d); return d; };
after(() => { for (const d of temps) try { rmSync(d, { recursive: true, force: true }); } catch {} });
const g = (cwd: string, ...a: string[]) => {
  const r = spawnSync("git", ["-c", "user.name=Person", "-c", "user.email=person@example.invalid", ...a], { cwd, encoding: "utf8" });
  return r.stdout.trim();
};

/** A person's repository: three commits on branch `trunk`, then an uncommitted edit and an untracked file. */
function personRepo(d: string, name = "my-app") {
  const r = join(d, name); mkdirSync(join(r, "src"), { recursive: true });
  g(r, "init", "-q", "-b", "trunk");
  writeFileSync(join(r, "README.md"), "# my app\n"); g(r, "add", "-A"); g(r, "commit", "-q", "-m", "First commit");
  writeFileSync(join(r, "src", "app.js"), "console.log('hi')\n"); g(r, "add", "-A"); g(r, "commit", "-q", "-m", "Add the app");
  writeFileSync(join(r, "src", "util.js"), "export const x = 1\n"); g(r, "add", "-A"); g(r, "commit", "-q", "-m", "Add a helper");
  writeFileSync(join(r, "README.md"), "# my app\nwork in progress\n");   // not committed
  writeFileSync(join(r, "notes.txt"), "untracked\n");                    // untracked
  return r;
}
/** Everything in the working folder except .git: path + bytes, hashed in sorted order. */
function treeHash(dir: string): string {
  const h = createHash("sha256"), walk = (p: string): string[] => readdirSync(p).flatMap(n => {
    if (n === ".git") return []; const f = join(p, n); return statSync(f).isDirectory() ? walk(f) : [f];
  });
  for (const f of walk(dir).sort()) h.update(relative(dir, f).replace(/\\/g, "/") + "\0").update(readFileSync(f)).update("\0");
  return h.digest("hex");
}
const snapshot = (r: string) => ({ head: g(r, "rev-parse", "HEAD"), branch: g(r, "symbolic-ref", "HEAD"), status: g(r, "status", "--porcelain"),
  index: g(r, "ls-files", "-s"), tree: treeHash(r), worktrees: g(r, "worktree", "list", "--porcelain").split("\n").filter(l => l.startsWith("worktree ")).length });

function newHost(d: string, replay = "codex_build.jsonl") {
  return new Host({ bind: BIND, port: 0, dir: join(d, "cfg"), projectsRoot: join(d, "projects"), agent: "replay",
    replayFile: join(STREAMS, replay), log: () => {} });
}
async function pairHeadset(h: Host, port: number, lastSeq = 0) {
  const a = await new HeadsetClient(BIND, port, h.id.fp).open();
  a.send({ type: "pair_token", token: h.pairing.token.value, device_name: "test headset" });
  const creds = await a.waitFor(f => f.type === "paired"); a.close();
  const c = await new HeadsetClient(BIND, port, h.id.fp).open();
  c.lastSeq = lastSeq;
  c.send({ type: "resume", device_id: creds.device_id, device_secret: creds.device_secret, last_seq: lastSeq });
  const welcome = await c.waitFor(f => f.type === "welcome");
  return { c, welcome, creds };
}

test("add registers the repository where it is, lists it with its recent commits, and is idempotent", () => {
  const d = tmp(), r = personRepo(d), before = snapshot(r);
  const h = newHost(d);
  const a = h.addRepo(join(r, "src"));                       // a folder inside the repository finds its top
  assert.deepEqual({ ...a, project_id: a.project_id }, { project_id: "p_my-app", name: "my-app", added: true, commits: 3 });
  assert.equal(h.projectDir("p_my-app").toLowerCase(), r.toLowerCase());
  assert.deepEqual(Object.keys(JSON.parse(readFileSync(join(d, "cfg", "repos.json"), "utf8"))), ["p_my-app"]);
  const evs = h.log.events.filter(e => e.project_id === "p_my-app");
  assert.equal(evs[0].kind, "project_created"); assert.equal(evs[0].name, "my-app");
  assert.deepEqual(evs.slice(1).map(e => e.title.replace(/^Commit [0-9a-f]+: /, "")), ["First commit", "Add the app", "Add a helper"]);
  for (const e of evs) { assert.ok(!BRAND.test(e.title)); assert.ok(!e.title.includes(d), "no local path in what the headset is sent"); }
  assert.deepEqual(h.state.summary().map(p => p.project_id), ["p_my-app"]);
  // again: same project, no new events
  const n = h.log.head;
  assert.equal(h.addRepo(r).added, false); assert.equal(h.log.head, n);
  // a second host on the same folder rebuilds it from disk
  assert.equal(newHost(d).projectDir("p_my-app").toLowerCase(), r.toLowerCase());
  // not a repository / no commits: refused with the next step, nothing registered
  const plain = join(d, "plain"); mkdirSync(plain);
  assert.throws(() => h.addRepo(plain), /not inside a git repository/);
  const empty = join(d, "empty"); mkdirSync(empty); g(empty, "init", "-q");
  assert.throws(() => h.addRepo(empty), /no commits yet/);
  assert.match(statusText(join(d, "cfg")), /p_my-app/);
  assert.deepEqual(snapshot(r), before, "adding reads only");
});

test("the CLI add command works with the host stopped", () => {
  const d = tmp(), r = personRepo(d, "Tool Box");
  const out = spawnSync(process.execPath, ["--experimental-strip-types", "--no-warnings", MAIN, "add", r, "--config", join(d, "cfg"), "--projects", join(d, "projects")], { encoding: "utf8" });
  assert.equal(out.status, 0, out.stderr);
  assert.match(out.stdout, /added Tool Box as p_tool-box/);
  assert.match(out.stdout, /new branch/);
});

test("protocol: a paired headset receives the added repository live and in its project list, beside a host-started project", async () => {
  const d = tmp(), r = personRepo(d);
  const h = newHost(d); const { port } = await h.start();
  try {
    const { c } = await pairHeadset(h, port);
    c.send({ type: "create_project", req_id: "c1", prompt: "new project: a clock [replay:codex_build.jsonl]" });
    const ok = await c.waitFor(f => f.type === "ok" && f.req_id === "c1");
    // the headset may not name a folder on the computer
    c.send({ type: "add_repo", req_id: "x1", path: r });
    assert.equal((await c.waitFor(f => f.type === "error" && f.req_id === "x1")).code, "unauthorized");
    // the command line on this computer adds it (same link and frames as `flowstate-host add` with the host running)
    const { c: cli } = await connectLocal(join(d, "cfg"));
    cli.send({ type: "add_repo", req_id: "a1", path: r });
    const added = await cli.waitFor(f => (f.type === "ok" || f.type === "error") && f.req_id === "a1");
    assert.equal(added.type, "ok"); assert.equal(added.project_id, "p_my-app"); cli.close();
    const live = await c.waitFor(f => f.type === "event" && f.kind === "project_created" && f.project_id === "p_my-app");
    assert.equal(live.name, "my-app");
    const last = await c.waitFor(f => f.type === "event" && f.kind === "message" && f.project_id === "p_my-app" && /Add a helper/.test(f.title));
    assert.match(last.commit, /^[0-9a-f]{40}$/);
    await c.waitFor(f => f.type === "event" && f.kind === "task_state" && f.task_id === ok.task_id && ["done", "failed"].includes(f.state), 30000);
    c.close();
    // a headset that connects now gets both in `welcome`
    const { c: c2, welcome } = await pairHeadset(h, port);
    const ids = welcome.projects.map((p: any) => p.project_id);
    assert.ok(ids.includes("p_my-app"), JSON.stringify(ids));
    assert.ok(ids.includes(ok.project_id), JSON.stringify(ids));
    assert.equal(welcome.projects.find((p: any) => p.project_id === "p_my-app").name, "my-app");
    c2.close();
    writeFileSync(join(d, "listed.json"), JSON.stringify({ host_added_repo_listed_to_client: 1, projects: ids }));
    if (process.env.ADD_REPO_OUT) writeFileSync(process.env.ADD_REPO_OUT, JSON.stringify({ host_added_repo_listed_to_client: 1, welcome_projects: welcome.projects, live_event: live, last_commit_event: last }, null, 1));
  } finally { await h.stop(); }
});

test("a task on an added repository works on a new branch in a separate folder; the person's checkout is untouched", async () => {
  const d = tmp(), r = personRepo(d), before = snapshot(r), branchesBefore = g(r, "branch", "--format=%(refname:short)");
  const h = newHost(d); const { port } = await h.start();
  try {
    h.addRepo(r);
    const { c } = await pairHeadset(h, port);
    c.send({ type: "prompt", req_id: "p1", project_id: "p_my-app", text: "add a clock module" });
    const ok = await c.waitFor(f => f.type === "ok" && f.req_id === "p1");
    const fin = await c.waitFor(f => f.type === "event" && f.kind === "finished" && f.task_id === ok.task_id, 30000);
    const end = await c.waitFor(f => f.type === "event" && f.kind === "task_state" && f.task_id === ok.task_id && ["done", "failed", "cancelled"].includes(f.state));
    c.close();
    assert.equal(end.state, "done", JSON.stringify(fin));
    assert.equal(fin.ok, true); assert.equal(fin.merged, false);
    assert.equal(fin.branch, `flowstate/${ok.task_id}`);
    assert.ok(fin.files.includes("main.js") && fin.files.includes("clock.js"), JSON.stringify(fin.files));
    // the person's repository: same HEAD, same branch, same index, same status, same working files, no extra worktree
    const after = snapshot(r);
    const touched = JSON.stringify(after) === JSON.stringify(before) ? 0 : 1;
    writeFileSync(join(d, "touch.json"), JSON.stringify({ host_touches_user_checkout: touched, before, after }));
    if (process.env.ADD_REPO_TOUCH_OUT) writeFileSync(process.env.ADD_REPO_TOUCH_OUT, JSON.stringify({ host_touches_user_checkout: touched, before, after, finished: fin }, null, 1));
    assert.deepEqual(after, before);
    assert.ok(!existsSync(join(r, ".worktrees")) && !existsSync(join(r, "main.js")));
    // the work is a new branch off the person's HEAD, holding the agent's files, not merged anywhere
    assert.equal(g(r, "rev-parse", `${fin.branch}^`), before.head);
    const onBranch = g(r, "show", "--name-only", "--format=", fin.branch).split("\n");
    assert.ok(onBranch.includes("clock.js") && onBranch.includes("main.js"), JSON.stringify(onBranch));
    assert.equal(g(r, "branch", "--format=%(refname:short)"), [...branchesBefore.split("\n"), fin.branch].sort().join("\n"));
    assert.equal(g(r, "rev-list", "--count", "trunk"), "3");
    assert.equal(g(r, "config", "--get", "remote.origin.url"), "", "nothing to push to, nothing pushed");
    assert.ok(!existsSync(join(d, "cfg", "worktrees", "my-app", ok.task_id)), "the task folder is removed once its work is committed");
  } finally { await h.stop(); }
});
