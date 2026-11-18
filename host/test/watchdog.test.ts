// #1052 host stall watchdog (offline, replayed agent streams). r07 in #1000 wrote its files, passed its own self-test,
// then emitted nothing until the run cap: the watchdog nudges such a run once, then ends it. A slow run that keeps
// emitting is never touched.
import { test, after } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, rmSync, readFileSync, readdirSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { spawnSync } from "node:child_process";
import { Host } from "../src/server.ts";

const STREAMS = join(new URL("./fixtures/streams/", import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, "$1"));
const temps: string[] = [];
after(() => { for (const d of temps) try { rmSync(d, { recursive: true, force: true }); } catch {} });

/** Run one task on a replayed stream with a fast watchdog; resolve with its events and the host's log lines. */
async function runOne(stream: string) {
  const d = mkdtempSync(join(tmpdir(), "fdog-")); temps.push(d);
  const lines: string[] = [];
  const h = new Host({ bind: "127.0.0.1", port: 0, dir: join(d, "cfg"), projectsRoot: join(d, "projects"), agent: "replay",
    replayFile: join(STREAMS, stream), log: s => lines.push(s), taskTimeoutMs: 20_000,
    watchdogQuietMs: 600, watchdogGraceMs: 400, watchdogTickMs: 50 });
  await h.start();
  const events: any[] = [];
  const done = new Promise<any>(res => h.log.follow(0, e => { events.push(e); if (e.kind === "task_state" && ["done", "failed", "cancelled"].includes(e.state!)) res(e); }));
  const p = h.createProject("new project: a tap counter");
  const t = h.enqueue(p, "new project: a tap counter", "replay");
  h.pump();
  const end = await done;
  await h.stop();
  const raw = readdirSync(join(d, "cfg", "raw")).map(f => readFileSync(join(d, "cfg", "raw", f), "utf8")).join("");
  return { h, t, end, events, lines, raw, pdir: h.projectDir(p) };
}

test("watchdog: an agent silent after writing files and passing its self-test is nudged once, then ends stalled_done and its work is merged", async () => {
  const r = await runOne("claude_stall.jsonl");
  const nudges = r.lines.filter(l => l.startsWith("host watchdog nudge")), ends = r.lines.filter(l => l.startsWith("host watchdog end"));
  assert.equal(nudges.length, 1, r.lines.join("\n"));
  assert.match(nudges[0], new RegExp(`^host watchdog nudge task=${r.t} quiet_s=\\d+$`));
  assert.equal(ends.length, 1);
  assert.match(ends[0], new RegExp(`^host watchdog end task=${r.t} quiet_s=\\d+ outcome=stalled_done$`));
  assert.equal((r.raw.match(/INPUT \{"nudge"/g) ?? []).length, 1, "the agent received exactly one nudge on stdin");
  const fin = r.events.find(e => e.kind === "finished" && e.task_id === r.t);
  assert.equal(fin.ok, true); assert.equal(fin.reason, "stalled_done"); assert.equal(fin.merged, true);
  assert.equal(r.end.state, "done");
  assert.match(spawnSync("git", ["show", "main:index.html"], { cwd: r.pdir, encoding: "utf8" }).stdout, /Tap/);
  for (const l of [...nudges, ...ends]) console.log(`# ${l}`);
});

test("watchdog: a slow agent that keeps emitting (200 ms gaps, 600 ms quiet limit, 2 s+ run) is never nudged and finishes normally", async () => {
  const r = await runOne("claude_slow.jsonl");
  assert.deepEqual(r.lines.filter(l => l.startsWith("host watchdog")), []);
  assert.equal(r.raw.includes('"nudge"'), false);
  const fin = r.events.find(e => e.kind === "finished" && e.task_id === r.t);
  assert.equal(fin.ok, true); assert.equal(fin.reason, undefined); assert.equal(fin.merged, true);
  const msgs = r.events.filter(e => e.kind === "message" && e.task_id === r.t);
  assert.ok(msgs.length >= 10, `expected the slow agent's 10 progress messages, got ${msgs.length}`);
  const t0 = Date.parse(msgs[0].at), t1 = Date.parse(msgs.at(-1).at);
  assert.ok(t1 - t0 >= 1000, `the run must outlast quiet+grace (1 s) to prove anything; took ${t1 - t0} ms`);
});
