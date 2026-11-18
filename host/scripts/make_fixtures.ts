// Writes the protocol fixtures (test/fixtures/protocol/*.json). Run once; the output is committed and is the contract.
// Values are fixed (not random) so another implementation can compare byte for byte, including the check code.
import { writeFileSync, mkdirSync } from "node:fs";
import { checkCode, pairUri } from "../src/protocol.ts";

const dir = new URL("../test/fixtures/protocol/", import.meta.url);
mkdirSync(dir, { recursive: true });
const fp = "3f1c9a0d6b2e47a8915c0e3d7f6a2b1c4d5e6f708192a3b4c5d6e7f8091a2b3c";
const token = "fixture-token-not-real"; // 22 chars like a real token; low entropy on purpose (secret scanners)
const code = "482915";
const dev = { device_id: "d_5a3c91e07b2f", device_secret: "fixture-device-secret-not-a-real-secret-000" };
const at = (s: number) => new Date(Date.UTC(2026, 10, 13, 6, 30, s)).toISOString();
const ev = (seq: number, s: number, kind: string, title: string, extra: object = {}) =>
  ({ dir: "h2c", type: "event", seq, at: at(s), project_id: "p_hello-world-cli-prints-time", run_id: "r_1f2e3d4c", kind, title, ...extra });

const fixtures: Record<string, { description: string; frames: any[]; qr?: string; check_inputs?: object }> = {
  pair: {
    description: "QR path: the client scans the URI, pins fp, sends the one-time token. Code path is identical with pair_code.",
    qr: pairUri({ h: "192.0.2.10", p: 47821, fp, t: token, n: "example-pc" }),
    check_inputs: { fp, token, code, check_for_token: checkCode(fp, token), check_for_code: checkCode(fp, code) },
    frames: [
      { dir: "c2h", type: "pair_token", token, device_name: "Headset" },
      { dir: "h2c", type: "paired", ...dev, host_name: "example-pc", check: checkCode(fp, token) },
    ],
  },
  pair_code: {
    description: "Code path after mDNS discovery: six digits typed by the wearer. Check must equal check_for_code in pair.json.",
    frames: [
      { dir: "c2h", type: "pair_code", code, device_name: "Headset" },
      { dir: "h2c", type: "paired", ...dev, host_name: "example-pc", check: checkCode(fp, code) },
    ],
  },
  resume: {
    description: "Reconnect after a drop: the client had processed seq 3; the host replays 4.. and then streams live. The open question is listed.",
    frames: [
      { dir: "c2h", type: "resume", ...dev, last_seq: 3 },
      { dir: "h2c", type: "welcome", host_name: "example-pc", head_seq: 5, open_decisions: ["perm-7c1d"], projects: [{ project_id: "p_hello-world-cli-prints-time", name: "hello world cli prints time", state: "running" }] },
      ev(4, 9, "command_run", "Ran node hello.js", { command: "node hello.js" }),
      ev(5, 9, "question", "Allow running node hello.js?", { decision_id: "perm-7c1d", options: [{ id: "allow", label: "Yes" }, { id: "deny", label: "No" }] }),
    ],
  },
  create_project: {
    description: "Start a new project by voice; the host answers ok with the project id, then events follow.",
    frames: [
      { dir: "c2h", type: "create_project", req_id: "c1", prompt: "new project: a hello-world CLI that prints the time", agent: "auto" },
      { dir: "h2c", type: "ok", req_id: "c1", project_id: "p_hello-world-cli-prints-time" },
      ev(1, 1, "started", "Agent started"),
    ],
  },
  prompt: {
    description: "A follow-up instruction to an existing project.",
    frames: [
      { dir: "c2h", type: "prompt", req_id: "c2", project_id: "p_hello-world-cli-prints-time", text: "also print the date" },
      { dir: "h2c", type: "ok", req_id: "c2", project_id: "p_hello-world-cli-prints-time" },
    ],
  },
  event: {
    description: "Every event kind the headset must render, in a realistic order.",
    frames: [
      ev(1, 1, "started", "Agent started"),
      ev(2, 4, "file_written", "Wrote hello.js", { path: "hello.js" }),
      ev(3, 5, "message", "I created the two files.", { detail: "I created the two files, ran the program, and committed both." }),
      ev(4, 9, "command_run", "Ran node hello.js", { command: "node hello.js" }),
      ev(5, 12, "finished", "Finished", { ok: true, exit_code: 0, commit: "0d7c3ed", files: ["README.md", "hello.js"] }),
      ev(6, 13, "error", "Run failed", { detail: "The assistant stopped before finishing." }),
      { ...ev(7, 14, "finished", "Stopped", { ok: false, exit_code: 1, reason: "canceled" }) },
    ],
  },
  decision_asked: {
    description: "The agent asks permission; the host turns it into a question with Yes/No.",
    frames: [ev(5, 9, "question", "Allow running node hello.js?", { decision_id: "perm-7c1d", options: [{ id: "allow", label: "Yes" }, { id: "deny", label: "No" }] })],
  },
  decision_answered: {
    description: "The wearer answers by voice or pinch; ok, then an answered event (seen by every paired client).",
    frames: [
      { dir: "c2h", type: "answer", req_id: "c3", decision_id: "perm-7c1d", option_id: "allow" },
      { dir: "h2c", type: "ok", req_id: "c3", project_id: "p_hello-world-cli-prints-time" },
      ev(6, 11, "answered", "You said yes", { decision_id: "perm-7c1d", option_id: "allow" }),
    ],
  },
  error: {
    description: "Refusals. Pairing errors close the socket with 4401 after the frame.",
    frames: [
      { dir: "c2h", type: "pair_code", code: "000000", device_name: "Headset" },
      { dir: "h2c", type: "error", code: "bad_code", message: "Wrong code. Check the six digits on the computer." },
      { dir: "h2c", type: "error", code: "token_expired", message: "That pairing code has expired. Show a new one." },
      { dir: "h2c", type: "error", code: "token_used", message: "That pairing code was already used." },
      { dir: "h2c", type: "error", code: "rate_limited", message: "Too many tries. Wait a minute." },
      { dir: "h2c", type: "error", code: "unknown_decision", message: "That question is no longer open.", req_id: "c4" },
    ],
  },
  cancel: {
    description: "Stop a run: the whole process tree is killed.",
    frames: [
      { dir: "c2h", type: "cancel", req_id: "c5", project_id: "p_hello-world-cli-prints-time" },
      { dir: "h2c", type: "ok", req_id: "c5", project_id: "p_hello-world-cli-prints-time" },
      ev(7, 14, "finished", "Stopped", { ok: false, exit_code: 1, reason: "canceled" }),
    ],
  },
};
for (const [name, f] of Object.entries(fixtures)) writeFileSync(new URL(`${name}.json`, dir), JSON.stringify(f, null, 2) + "\n");
console.log(Object.keys(fixtures).join(" "));
