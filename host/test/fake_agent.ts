// Offline stand-in for a coding CLI: replays a recorded stream (test/fixtures/streams/*.jsonl) on stdout and
// performs its recorded side effects in the cwd, so the folder really ends up with working code and a commit.
// Writes files from Write tool calls; runs recorded `node ...` and `git ...` commands; waits on stdin for the
// answer to a recorded permission question. Usage: node fake_agent.ts <stream.jsonl>
import { readFileSync, writeFileSync, mkdirSync } from "node:fs";
import { dirname, join } from "node:path";
import { spawnSync } from "node:child_process";
import { createInterface } from "node:readline";

const cwd = process.cwd();
const delay = Number(process.env.FAKE_AGENT_DELAY_MS ?? 40);
const jsonCwd = JSON.stringify(cwd).slice(1, -1);
const lines = readFileSync(process.argv[2], "utf8").split("\n").filter(l => l.trim()).map(l => l.split("{{CWD}}").join(jsonCwd));
const answers: string[] = []; let wake: (() => void) | null = null;
// #1035: a nudge carrying the wearer's words gets a short reply, in the stream's own shape.
const codexShape = lines.some(l => l.includes('"thread.started"'));
const reply = (text: string) => process.stdout.write(JSON.stringify(codexShape
  ? { type: "item.completed", item: { type: "agent_message", text } }
  : { type: "assistant", message: { content: [{ type: "text", text }] } }) + "\n");
createInterface({ input: process.stdin }).on("line", l => {
  if (!l.includes('"answer"')) {
    process.stderr.write(`INPUT ${l}\n`);
    try { const n = JSON.parse(l).nudge; if (typeof n === "string" && n.includes("talking to you from the headset")) reply("I heard you. I am still on this task and will finish it shortly. Nothing else changes."); } catch { /* the prompt line */ }
    return;
  }
  answers.push(l); wake?.();
}); // first line is the prompt
const sleep = (ms: number) => new Promise(r => setTimeout(r, ms));
// Fixtures were recorded on Windows (`{{CWD}}\hello.py`): on macOS/Linux a backslash is part of a file name, so normalise it (#954).
const local = (raw: string) => { const p = process.platform === "win32" ? raw : raw.replace(/\\/g, "/"); return p.startsWith(cwd) ? p : join(cwd, p); };

for (const line of lines) {
  await sleep(delay);
  const m = JSON.parse(line);
  // #1052 directives (never printed): `_sleep_ms` waits, `_stall` goes silent for good (a nudge on stdin is only logged).
  if (m._sleep_ms) { await sleep(m._sleep_ms); continue; }
  if (m._stall) { setInterval(() => {}, 60_000); await new Promise(() => {}); }
  process.stdout.write(line + "\n");
  if (m.type === "control_request" && m.request?.subtype === "can_use_tool") {
    while (!answers.length) await new Promise<void>(r => { wake = r; });
    const a = JSON.parse(answers.shift()!);
    if (a.opt !== "allow") { process.stdout.write(JSON.stringify({ type: "result", subtype: "success", is_error: false, result: "stopped: permission refused" }) + "\n"); process.exit(0); }
  }
  if (m.type === "item.completed" && m.item?.type === "file_change") for (const ch of m.item.changes ?? []) {
    if (typeof ch._content !== "string") continue;
    const f = local(ch.path); mkdirSync(dirname(f), { recursive: true }); writeFileSync(f, ch._content);
  }
  if (m.type !== "assistant") continue;
  for (const c of m.message?.content ?? []) {
    if (c.type !== "tool_use") continue;
    if (c.name === "Write" && c.input?.file_path) { const f = local(c.input.file_path); mkdirSync(dirname(f), { recursive: true }); writeFileSync(f, c.input.content ?? ""); }
    if (c.name === "Bash" && typeof c.input?.command === "string") {
      const cmd: string = c.input.command;
      if (/^git (add|commit)/.test(cmd)) {
        spawnSync("git", ["add", "-A"], { cwd });
        const msg = cmd.match(/-m "([^"\n]*)/)?.[1] ?? "agent commit";
        spawnSync("git", ["commit", "-q", "-m", msg], { cwd });
      } else if (/^node [\w.\/-]+$/.test(cmd)) spawnSync("node", cmd.split(" ").slice(1), { cwd, stdio: "ignore" });
    }
  }
}
process.exit(0);
