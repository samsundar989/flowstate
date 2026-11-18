// #815 "new project by voice": the request id travels into the log so a headset that lost the `ok` (Wi-Fi drop) can
// resend without starting a second project, and `finished` carries the first line the program printed.
import { test, after } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, rmSync, readdirSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { Host } from "../src/server.ts";
import { HeadsetClient } from "../src/client.ts";
import { CodexConverter, ClaudeConverter, firstLine } from "../src/agents.ts";
import { validate } from "../src/protocol.ts";

const STREAMS = join(new URL("./fixtures/streams/", import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, "$1"));
const BIND = process.env.HOST_TEST_BIND ?? "127.0.0.1";
const temps: string[] = [];
after(() => { for (const d of temps) try { rmSync(d, { recursive: true, force: true }); } catch {} });

async function paired(h: Host, port: number) {
  const a = await new HeadsetClient(BIND, port, h.id.fp).open();
  a.send({ type: "pair_token", token: h.pairing.token.value, device_name: "test" });
  const creds = await a.waitFor(f => f.type === "paired"); a.close();
  const c = await new HeadsetClient(BIND, port, h.id.fp).open();
  c.send({ type: "resume", device_id: creds.device_id, device_secret: creds.device_secret, last_seq: 0 });
  await c.waitFor(f => f.type === "welcome");
  return c;
}

test("a resent create_project (same req_id) is answered with the same ids and makes one project; the log names the request", async () => {
  const d = mkdtempSync(join(tmpdir(), "fvp-")); temps.push(d);
  const h = new Host({ bind: BIND, port: 0, dir: join(d, "cfg"), projectsRoot: join(d, "projects"), agent: "replay",
    replayFile: join(STREAMS, "codex_build.jsonl"), log: () => {} });
  const { port } = await h.start();
  try {
    const c = await paired(h, port);
    const prompt = "a command-line tool that prints the time";
    c.send({ type: "create_project", req_id: "c_voice1", prompt });
    const ok1 = await c.waitFor(f => f.type === "ok" && f.req_id === "c_voice1");
    c.send({ type: "create_project", req_id: "c_voice1", prompt });       // the headset never saw ok1
    const oks = [ok1, await c.waitFor(f => f.type === "ok" && f.req_id === "c_voice1" && f !== ok1)];
    assert.deepEqual(oks.map(o => [o.project_id, o.task_id]), [[ok1.project_id, ok1.task_id], [ok1.project_id, ok1.task_id]]);
    const fin = await c.waitFor(f => f.type === "event" && f.kind === "finished" && f.task_id === ok1.task_id, 30000);
    assert.equal(readdirSync(join(d, "projects")).length, 1, "one project folder");
    const created = h.log.events.filter(e => e.kind === "project_created");
    assert.equal(created.length, 1);
    assert.equal(created[0].req_id, "c_voice1");
    assert.equal(h.log.events.find(e => e.kind === "task_state" && e.state === "queued")!.req_id, "c_voice1");
    // the fixture's agent ran `node main.js` and it printed this
    assert.equal(fin.ok, true); assert.match(fin.commit, /^[0-9a-f]{7,}$/);
    assert.equal(fin.output, "The time is 10:00:00");
    for (const e of h.log.events) assert.equal(validate(e, "h2c"), null, JSON.stringify(e));
    c.close();
  } finally { await h.stop(); }
});

test("converters keep the first output line of the last command that succeeded, never a failed one", () => {
  const cx = new CodexConverter("C:/w"), none = () => {};
  cx.feed(JSON.stringify({ type: "item.completed", item: { type: "command_execution", command: "python t.py", aggregated_output: "\n  Hello at 10:00\nsecond\n", exit_code: 0 } }), none);
  cx.feed(JSON.stringify({ type: "item.completed", item: { type: "command_execution", command: "bad", aggregated_output: "Traceback", exit_code: 1 } }), none);
  assert.equal(cx.output, "Hello at 10:00");
  const cl = new ClaudeConverter("C:/w");
  cl.feed(JSON.stringify({ type: "assistant", message: { content: [{ type: "tool_use", id: "u1", name: "Bash", input: { command: "node c.js" } }, { type: "tool_use", id: "u2", name: "Write", input: { file_path: "C:/w/x" } }] } }), none);
  cl.feed(JSON.stringify({ type: "user", message: { content: [{ type: "tool_result", tool_use_id: "u2", content: "File created" }] } }), none);
  assert.equal(cl.output, undefined, "a file write is not program output");
  cl.feed(JSON.stringify({ type: "user", message: { content: [{ type: "tool_result", tool_use_id: "u1", content: [{ type: "text", text: "Count: 0\n" }] }] } }), none);
  assert.equal(cl.output, "Count: 0");
  assert.equal(firstLine("\r\n\r\nx\r\ny"), "x");
});
