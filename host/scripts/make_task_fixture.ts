// Records test/fixtures/protocol/task_flow.json from a real offline host run (replayed agent), so the fixture is
// exactly what the host sends: new project -> question -> answer -> resumed -> merged; a second task by prompt,
// cancelled with task_id; a free-text answer form. Run: node --experimental-strip-types scripts/make_task_fixture.ts
import { mkdtempSync, writeFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { Host } from "../src/server.ts";
import { HeadsetClient } from "../src/client.ts";

process.env.FAKE_AGENT_DELAY_MS = "150";
const streams = new URL("../test/fixtures/streams/", import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, "$1");
const d = mkdtempSync(join(tmpdir(), "ffix-"));
const h = new Host({ bind: "127.0.0.1", port: 0, dir: join(d, "cfg"), projectsRoot: join(d, "projects"), agent: "replay", replayFile: join(streams, "codex_ask.jsonl"), log: () => {} });
const { port } = await h.start();
const a = await new HeadsetClient("127.0.0.1", port, h.id.fp).open();
a.send({ type: "pair_token", token: h.pairing.token.value, device_name: "fixture" });
const p = await a.waitFor(f => f.type === "paired"); a.close();
const c = await new HeadsetClient("127.0.0.1", port, h.id.fp).open();
const frames: any[] = [];
const out = (f: any) => { c.send(f); frames.push({ dir: "c2h", ...f }); };
const until = async (pred: (f: any) => boolean) => { for (;;) { const f = await c.waitFor(() => true, 20000); frames.push({ dir: "h2c", ...f }); if (pred(f)) return f; } };
out({ type: "resume", device_id: p.device_id, device_secret: p.device_secret, last_seq: 0 });
await until(f => f.type === "welcome");
out({ type: "create_project", req_id: "c1", prompt: "new project: a hello-world CLI that prints the time. Ask me which language before writing.", name: "time cli" });
const q = await until(f => f.kind === "task_state" && f.state === "needs_answer");
const dec = frames.find(f => f.kind === "question");
out({ type: "answer", req_id: "a1", decision_id: dec.decision_id, option_id: "o1" });
const done = await until(f => f.kind === "task_state" && f.state === "done");
out({ type: "prompt", req_id: "c2", project_id: done.project_id, text: "add a second clock module [replay:codex_build.jsonl]" });
const ok2 = await until(f => f.type === "ok" && f.req_id === "c2");
await until(f => f.kind === "file_written");
out({ type: "cancel", req_id: "k1", project_id: done.project_id, task_id: ok2.task_id });
await until(f => f.kind === "task_state" && f.state === "cancelled");
c.close(); await h.stop();
// the device secret never belongs in a fixture
frames.find(f => f.type === "prompt").text = "add a second clock module"; // drop the replay hook (a test-only marker)
frames[0].device_id = "d_3f9a1c2b7e4d"; frames[0].device_secret = "<device_secret>";
writeFileSync(new URL("../test/fixtures/protocol/task_flow.json", import.meta.url), JSON.stringify({
  description: "Recorded from a real offline host run. New project by voice -> task t1 queued/running -> the agent asks (question with options, allow_text) -> needs_answer -> answer -> answered -> the same agent session resumes -> finished (merged into main, commit) -> done. Then a second task in the same project by `prompt`, cancelled with `task_id` while writing: its worktree is kept and named. An answer may also be free text: {\"type\":\"answer\",\"req_id\":\"a2\",\"decision_id\":\"q_t1_1\",\"text\":\"Python, please\"}.",
  frames }, null, 2) + "\n");
console.log(`${frames.length} frames; question ${q.seq}`);
try { rmSync(d, { recursive: true, force: true }); } catch { /* temp */ }
process.exit(0);
