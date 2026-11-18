// Converter unit tests: stream-json and hook payloads -> valid schema events, with paths/keys redacted.
import { test } from "node:test";
import assert from "node:assert/strict";
import { homedir } from "node:os";
import { join } from "node:path";
import { Converter } from "../src/claude.ts";
import { checkStream, dagStats } from "../src/schema.ts";

const cwd = join(homedir(), "toy");
const ts = (s: number) => new Date(Date.UTC(2026, 10, 3, 12, 0, s)).toISOString();
const lines = [
  { type: "system", subtype: "init", cwd, session_id: "abc" },
  { type: "assistant", timestamp: ts(1), parent_tool_use_id: null, message: { id: "m1", content: [{ type: "text", text: "Let me look." }, { type: "tool_use", id: "toolu_AAAA1111", name: "Read", input: { file_path: join(cwd, "calc.py") } }, { type: "tool_use", id: "toolu_BBBB2222", name: "Grep", input: { pattern: "def " } }] } },
  { type: "user", timestamp: ts(2), message: { content: [{ type: "tool_result", tool_use_id: "toolu_AAAA1111", content: "def add(a, b): ..." }, { type: "tool_result", tool_use_id: "toolu_BBBB2222", content: "2 matches" }] } },
  { type: "assistant", timestamp: ts(3), message: { id: "m2", content: [{ type: "tool_use", id: "toolu_CCCC3333", name: "Bash", input: { command: "python -m pytest -q", description: "run tests" } }] } },
  { type: "user", timestamp: ts(9), message: { content: [{ type: "tool_result", tool_use_id: "toolu_CCCC3333", is_error: true, content: `1 failed in ${cwd}/test_calc.py key ${['sk', 'ant', 'api03', 'SECRETSECRET'].join('-')}` }] } },
  { type: "assistant", timestamp: ts(10), message: { id: "m3", content: [{ type: "tool_use", id: "toolu_DDDD4444", name: "Bash", input: { command: "python -m pytest -q", description: "run tests" } }] } },
  { type: "user", timestamp: ts(15), message: { content: [{ type: "tool_result", tool_use_id: "toolu_DDDD4444", content: "3 passed" }] } },
  { type: "result", subtype: "success", is_error: false, result: "All good." },
];

test("stream-json converts to a valid stream with fan-out, fan-in, retry and redaction", () => {
  const c = new Converter("builder", { cwd, t0: Date.parse(ts(0)) });
  const evs = lines.flatMap((l) => c.feedStreamJson(JSON.stringify(l), "Fix the tests"));
  assert.deepEqual(checkStream(evs.map((e, i) => ({ ...e, t_ms: i }))), []);
  const s = dagStats(evs);
  assert.equal(s.steps, 5); // root + 4 tools
  assert.equal(s.fanin_max, 3); // first Bash: parent root + depends_on both parallel reads (fan-in)
  assert.equal(s.retries, 1);
  assert.equal(s.failures, 1);
  const blob = JSON.stringify(evs);
  assert.ok(!blob.includes(JSON.stringify(homedir()).slice(1, -1)), "home path leaked");
  assert.ok(!blob.includes("SECRETSECRET"), "key leaked");
  assert.equal(evs.at(-1)!.status, "COMPLETED");
});

test("hook payloads convert to a valid stream", () => {
  const c = new Converter("reviewer", { cwd });
  const hooks = [
    { hook_event_name: "UserPromptSubmit", prompt: "Review calc.py" },
    { hook_event_name: "PreToolUse", tool_name: "Read", tool_use_id: "toolu_X1", tool_input: { file_path: join(cwd, "calc.py") } },
    { hook_event_name: "PostToolUse", tool_name: "Read", tool_use_id: "toolu_X1", tool_response: "ok" },
    { hook_event_name: "Notification", message: "Claude needs your permission to use Bash" },
    { hook_event_name: "Stop" },
  ];
  const evs = hooks.flatMap((h) => c.feedHook(h));
  assert.deepEqual(checkStream(evs), []);
  assert.deepEqual(evs.map((e) => e.kind), ["task_assigned", "step_start", "status", "step_start", "tool_call", "tool_result", "step_end", "message", "step_end", "done", "status"]);
});
