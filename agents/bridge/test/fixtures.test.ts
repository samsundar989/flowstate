// Acceptance: every fixture validates; each replays at 8x over WebSocket to a real client (>= 50 events),
// the client answers INPUT_REQUIRED by hand and its task_assigned comes back as an event.
import { test, describe } from "node:test";
import assert from "node:assert/strict";
import { readdirSync } from "node:fs";
import { resolve } from "node:path";
import WebSocket from "ws";
import { FIXTURES_DIR, readJsonl, checkStream, dagStats, validateEvent, validateClientMessage, type AgentEvent } from "../src/schema.ts";
import { Replay } from "../src/replay.ts";
import { SCENARIOS, synthesise } from "../src/gen_fixtures.ts";
import { BRAND_DENYLIST, brandMatches, textValues } from "../src/brands.ts";

const fixtures = readdirSync(FIXTURES_DIR).filter((f) => f.endsWith(".jsonl")).sort();
const isShot = (f: string) => f.startsWith("shot_");
// Recorded real day (#87): 20 h of wall time, seven stations; not a synthetic three-role scenario.
const isDay = (f: string) => f === "real_autoflow_day.jsonl";
const SHOTS = ["shot_builder_csv_export.jsonl", "shot_reviewer_needs_input.jsonl", "shot_wrong_branch_redirect.jsonl"];

test("brand denylist is case-insensitive and whole-word", () => {
  for (const brand of BRAND_DENYLIST) {
    assert.deepEqual(brandMatches(`(${brand.toUpperCase()})`), [brand.toUpperCase()]);
    assert.deepEqual(brandMatches(`prefix${brand}suffix`), []);
  }
  assert.deepEqual(brandMatches("pytest npm python typescript metadata requests"), []);
  // #202: "gh" is whole-word only; the hyphenated pages product reports as one hit.
  assert.deepEqual(brandMatches("high through thigh weigh ghost laugh ghp"), []);
  assert.deepEqual(brandMatches("no gh CLI, use the gh-pages branch or GH Pages"), ["gh", "gh-pages", "GH Pages"]);
  assert.deepEqual(brandMatches("deploy to fly.io, not flyxio"), ["fly.io"]);
});

test("fixtures contain no company or commercial product names in user-visible text", () => {
  for (const f of fixtures) {
    for (const [i, event] of readJsonl(resolve(FIXTURES_DIR, f)).entries()) {
      for (const text of textValues(event)) {
        assert.deepEqual(brandMatches(text), [], `${f}:${i + 1}: ${text}`);
      }
    }
  }
});

test("fixture set is complete", () => {
  assert.ok(fixtures.filter((f) => f !== "real_claude_run.jsonl").length >= 3, "need >= 3 synthetic fixtures");
  assert.ok(fixtures.includes("real_claude_run.jsonl"), "real_claude_run.jsonl missing");
  for (const s of SHOTS) assert.ok(fixtures.includes(s), `${s} missing`);
});

test("synthetic fixtures are exactly what gen_fixtures.ts produces (seeded, deterministic)", () => {
  for (const sc of SCENARIOS) {
    assert.deepEqual(readJsonl(resolve(FIXTURES_DIR, `${sc.name}.jsonl`)), synthesise(sc), `${sc.name}: run npm run gen-fixtures`);
  }
});

// Shot fixtures (task #52): each has a scenario label and the beats its video shot needs.
const at = (evs: AgentEvent[], pred: (e: AgentEvent) => boolean) => evs.findIndex(pred);
const finalStatus = (evs: AgentEvent[], a: string) => evs.filter((e) => e.agent_id === a && e.kind === "status").at(-1)?.status;
test("shot fixtures: labelled, valid, and show their beat", () => {
  for (const s of SHOTS) {
    const evs = readJsonl(resolve(FIXTURES_DIR, s));
    assert.deepEqual(checkStream(evs), [], s);
    assert.match(evs[0].detail ?? "", /^Scripted scenario: /, `${s}: scenario label on first event`);
    assert.equal(dagStats(evs).failures, 0, `${s}: no failures`);
    for (const a of ["researcher", "builder", "reviewer"]) assert.equal(finalStatus(evs, a), "COMPLETED", `${s}: ${a} ends COMPLETED`);
  }
  { // Reviewer needs input: 90-120 s, INPUT_REQUIRED ~20 s, two tool calls after the answer, others busy meanwhile.
    const evs = readJsonl(resolve(FIXTURES_DIR, "shot_reviewer_needs_input.jsonl"));
    const s = dagStats(evs);
    assert.ok(s.duration_s >= 90 && s.duration_s <= 120, `duration ${s.duration_s}`);
    const q = evs.filter((e) => e.kind === "status" && e.status === "INPUT_REQUIRED");
    assert.equal(q.length, 1);
    assert.equal(q[0].agent_id, "reviewer");
    assert.ok(q[0].t_ms >= 17_000 && q[0].t_ms <= 23_000, `INPUT_REQUIRED at ${q[0].t_ms} ms`);
    assert.deepEqual(q[0].options!.map((o) => o.gesture).sort(), ["thumbs_down", "thumbs_up"], "yes/no question");
    const ans = at(evs, (e) => e.agent_id === "reviewer" && e.answer !== undefined);
    assert.equal(evs.slice(ans).filter((e) => e.agent_id === "reviewer" && e.kind === "tool_call").length, 2);
    const answeredAt = evs[ans].t_ms;
    for (const a of ["researcher", "builder"]) assert.ok(evs.some((e) => e.agent_id === a && e.kind === "tool_call" && e.t_ms > answeredAt), `${a} works after the answer`);
  }
  { // Builder CSV export: 8-12 builder steps, exactly one artifact with a file name, at the builder's end.
    const evs = readJsonl(resolve(FIXTURES_DIR, "shot_builder_csv_export.jsonl"));
    const steps = evs.filter((e) => e.kind === "step_start" && e.agent_id === "builder" && e.step_id !== "builder.root");
    assert.ok(steps.length >= 8 && steps.length <= 12, `builder steps ${steps.length}`);
    assert.match(evs[0].title!, /CSV/);
    const arts = evs.filter((e) => e.kind === "artifact");
    assert.equal(arts.length, 1);
    assert.match(arts[0].title!, /\.csv$/);
    const lastToolOrArtifact = evs.filter((e) => e.agent_id === "builder" && ["tool_result", "artifact", "step_start"].includes(e.kind)).at(-1);
    assert.equal(lastToolOrArtifact?.kind, "artifact", "artifact comes last in the builder's track");
  }
  { // Wrong branch: INPUT_REQUIRED answered thumbs-down, then CANCELED step + status, restart, COMPLETED.
    const evs = readJsonl(resolve(FIXTURES_DIR, "shot_wrong_branch_redirect.jsonl"));
    const q = at(evs, (e) => e.kind === "status" && e.status === "INPUT_REQUIRED");
    assert.equal(evs[q].agent_id, "builder");
    assert.match(evs[q].title!, /continue on feature\/x\?/i);
    const chosen = evs[q].options!.find((o) => o.id === evs[q].default_option)!;
    assert.equal(chosen.gesture, "thumbs_down");
    const ans = at(evs, (e) => e.answer !== undefined);
    assert.equal(evs[ans].answer, chosen.id);
    const cancelEnd = at(evs, (e) => e.kind === "step_end" && e.status === "CANCELED");
    const cancelStatus = at(evs, (e) => e.kind === "status" && e.status === "CANCELED");
    assert.ok(ans < cancelEnd && cancelEnd < cancelStatus, "answer -> step_end CANCELED -> status CANCELED");
    const restart = evs.slice(cancelStatus).find((e) => e.kind === "step_start" && e.agent_id === "builder");
    assert.ok(restart && /main/.test(restart.title!) && !restart.depends_on?.includes(evs[cancelEnd].step_id!), "restart on main, not gated on the cancelled step");
  }
});

test("client message schema", () => {
  assert.equal(validateClientMessage({ kind: "task_assigned", agent_id: "builder", task: "Fix it", priority: "high" }), null);
  assert.equal(validateClientMessage({ kind: "input_response", agent_id: "reviewer", step_id: "v3", option_id: "approve" }), null);
  assert.notEqual(validateClientMessage({ kind: "task_assigned", agent_id: "builder" }), null);
  assert.notEqual(validateEvent({ t_ms: 0, agent_id: "a", kind: "step_start" }), null, "step_start without step_id must fail");
});

describe("fixtures (replays run concurrently)", { concurrency: true }, () => {
for (const [i, f] of fixtures.entries()) {
  test(`${f}: validates against schema and stream invariants`, () => {
    const evs = readJsonl(resolve(FIXTURES_DIR, f));
    assert.deepEqual(checkStream(evs), []);
    const s = dagStats(evs);
    if (f !== "real_claude_run.jsonl" && f !== "autoflow_sample.jsonl" && !isShot(f) && !isDay(f)) {
      assert.equal(s.agents, 3);
      assert.ok(s.steps >= 8 && s.steps <= 23, `steps ${s.steps}`);
      assert.ok(s.duration_s >= 240 && s.duration_s <= 480, `duration ${s.duration_s}s`);
      assert.ok(s.fanout_max >= 2 && s.fanin_max >= 2, "needs fan-out and fan-in");
      assert.ok(s.failures >= 1 && s.retries >= 1 && s.input_required >= 1);
    }
  });

  test(`${f}: replays at 8x to a WebSocket client`, { timeout: 120_000 }, async () => {
    const evs = readJsonl(resolve(FIXTURES_DIR, f));
    const port = 0; // OS-assigned: other worker worktrees run this suite concurrently.
    const r = new Replay(evs, { port, speed: isDay(f) ? 10_000_000 : 8, autoAnswerS: 20, log: false });
    await r.start();
    const got: AgentEvent[] = [];
    const ws = new WebSocket(`ws://127.0.0.1:${(r.hub.wss.address() as { port: number }).port}`);
    const opened = new Promise((res) => ws.once("open", res));
    let answered = 0;
    ws.on("message", (b) => {
      const e = JSON.parse(b.toString()) as AgentEvent;
      got.push(e);
      if (e.kind === "status" && e.status === "INPUT_REQUIRED") {
        answered++;
        ws.send(JSON.stringify({ kind: "input_response", agent_id: e.agent_id, step_id: e.step_id, option_id: e.options![0].id }));
      }
    });
    await opened; // handler attached first: history can arrive in the same tick as 'open'
    ws.send(JSON.stringify({ kind: "task_assigned", agent_id: "builder", task: "Also update the changelog", priority: "high" }));
    await r.done;
    await new Promise((res) => setTimeout(res, 100));
    ws.close();
    await r.stop();
    const nonEcho = got.filter((e) => !(e.kind === "task_assigned" && e.title === "Also update the changelog"));
    assert.equal(nonEcho.length, evs.length, "every fixture event delivered");
    assert.ok(got.length >= (isShot(f) ? 30 : 50), `only ${got.length} events`);
    assert.ok(got.some((e) => e.kind === "task_assigned" && e.title === "Also update the changelog" && e.priority === "high"), "task_assigned echo");
    for (let k = 1; k < got.length; k++) assert.ok(got[k].t_ms >= got[k - 1].t_ms, "wire t_ms non-decreasing");
    for (const e of got) assert.equal(validateEvent(e), null);
    const resumed = got.filter((e) => e.kind === "status" && e.answer !== undefined);
    // The recorded day replays its three historical answers; it has no step-level INPUT_REQUIRED to answer.
    assert.equal(resumed.length, isDay(f) ? 3 + answered : answered);
    for (const e of resumed) assert.match(e.title!, f === "autoflow_sample.jsonl" ? /^Decision resolved/ : isDay(f) ? /^Recorded answer$/ : /^User chose:/);
    console.log(`# ${f}: ${got.length} events received, ${answered} input(s) answered by client, ${JSON.stringify(dagStats(evs))}`);
  });
}
});
