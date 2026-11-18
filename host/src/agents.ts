// Agent runner: starts the user's own installed, logged-in coding CLI in a project folder, turns its stream into
// host events, answers its permission questions, kills the whole process tree on cancel. Reads no credentials.
import { spawn, spawnSync, type ChildProcess } from "node:child_process";
import { createInterface } from "node:readline";
import { relative, isAbsolute } from "node:path";
import { fileURLToPath } from "node:url";
import type { NewEvent } from "./eventlog.ts";

export type AgentKind = "claude" | "codex" | "replay";
type Emit = (e: Omit<NewEvent, "project_id" | "run_id">) => void;
const BRANDS = /\b(claude|anthropic|codex|openai|powershell|gpt[-\w.]*|chatgpt|opus|sonnet|haiku)\b/gi;

/** Text safe for the headset: no vendor names, no absolute paths, no key-shaped strings, short. */
export function clean(s: string, cwd: string, max = 280): string {
  let o = String(s ?? "");
  for (const v of [cwd, cwd.replace(/\\/g, "/"), cwd.replace(/\\/g, "\\\\")]) if (v) o = o.split(v + (v.includes("/") ? "/" : "\\")).join("").split(v).join(".");
  o = o.replace(/[A-Za-z]:\\[^\s"'`]+|\/(?:Users|home)\/[^\s"'`]+/g, "[path]")
    .replace(/\b(sk-[A-Za-z0-9_-]{16,}|gh[pousr]_[A-Za-z0-9]{20,}|AKIA[0-9A-Z]{16})\b/g, "[redacted]")
    .replace(BRANDS, "assistant").replace(/\s+/g, " ").trim();
  return o.length > max ? o.slice(0, max - 1) + "…" : o;
}
const rel = (p: string, cwd: string) => { const r = isAbsolute(p) ? relative(cwd, p) : p; return r.replace(/\\/g, "/"); };
/** Strip the shell wrapper one CLI puts around every command. */
const unwrap = (c: string) => { const m = c.match(/^"?[^"]*powershell(?:\.exe)?"?\s+-Command\s+(['"])([\s\S]*)\1$/i); return m ? m[2] : c; };

// ---- stream converters (pure: one raw line in, events out) --------------------------------------------------
/** The agent's closing answer, in the shape the host asks for (codex enforces it with --output-schema). */
export interface Final { status: "done" | "question"; summary: string; question: string; options: string[]; parts: RawPart[] }
/** A part as the agent wrote it (#972 plan phase); the host cleans and checks it before it becomes a `plan` event. */
export interface RawPart { id: string; name: string; files: string[] }
const rawParts = (v: unknown): RawPart[] => Array.isArray(v) ? v.filter(p => p && typeof p === "object").slice(0, 6)
  .map((p: any) => ({ id: String(p.id ?? ""), name: String(p.name ?? ""), files: Array.isArray(p.files) ? p.files.map(String).slice(0, 6) : [] })) : [];
export function parseFinal(text: string): Final | undefined {
  const t = String(text ?? "").trim();
  for (const cand of [t, t.split("\n").filter(l => l.trim().startsWith("{")).at(-1) ?? "", t.replace(/^```(?:json)?\s*|\s*```$/g, "")]) {
    try {
      const o = JSON.parse(cand);
      if (o && (o.status === "done" || o.status === "question")) return { status: o.status, summary: String(o.summary ?? ""), question: String(o.question ?? ""), options: Array.isArray(o.options) ? o.options.map(String).filter(Boolean).slice(0, 6) : [], parts: rawParts(o.parts) };
    } catch { /* not the closing answer */ }
  }
  return undefined;
}

/** #1052: what the watchdog needs to know about a run: has it written files, and did a command succeed after the last write (its self-test)? */
export interface Progress { wrote: boolean; checked: boolean }

export class ClaudeConverter {
  started = false; ok: boolean | undefined; session?: string; final?: Final; private cwd: string;
  progress: Progress = { wrote: false, checked: false };
  /** #815: first output line of the last command that succeeded. */
  output?: string; private shellIds = new Set<string>();
  constructor(cwd: string) { this.cwd = cwd; }
  feed(line: string, emit: Emit, ask?: (id: string, tool: string, input: any) => void) {
    let m: any; try { m = JSON.parse(line); } catch { return; }
    if (m.type === "system" && m.subtype === "init" && !this.started) { this.started = true; this.session = m.session_id; emit({ kind: "started", title: "Agent started" }); }
    if (m.type === "assistant") for (const c of m.message?.content ?? []) {
      const fin = c.type === "text" ? parseFinal(c.text) : undefined;
      if (fin) { this.final = fin; if (fin.status === "done" && fin.summary) emit({ kind: "message", title: clean(fin.summary, this.cwd, 60), detail: clean(fin.summary, this.cwd, 2000) }); continue; }
      if (c.type === "text" && c.text?.trim()) emit({ kind: "message", title: clean(c.text, this.cwd, 60), detail: clean(c.text, this.cwd, 2000) });
      if (c.type === "tool_use") {
        const i = c.input ?? {};
        if (["Write", "Edit", "MultiEdit", "NotebookEdit"].includes(c.name) && (i.file_path || i.notebook_path)) {
          const p = rel(i.file_path ?? i.notebook_path, this.cwd); this.progress = { wrote: true, checked: false }; emit({ kind: "file_written", title: `Wrote ${clean(p, this.cwd, 60)}`, path: clean(p, this.cwd, 200) });
        } else if (["Bash", "PowerShell"].includes(c.name) && i.command) {
          const cmd = clean(i.command, this.cwd, 200); emit({ kind: "command_run", title: `Ran ${cmd.slice(0, 50)}`, command: cmd });
          if (c.id) this.shellIds.add(c.id);
        }
      }
    }
    if (m.type === "user") for (const c of m.message?.content ?? []) if (c?.type === "tool_result" && this.shellIds.has(c.tool_use_id) && !c.is_error) {
      const l = firstLine(c.content); if (l) this.output = l;
      if (this.progress.wrote) this.progress.checked = true;
    }
    if (m.type === "system" && m.subtype === "permission_denied") emit({ kind: "message", title: "A step was blocked", detail: clean(m.message ?? "", this.cwd) });
    if (m.type === "control_request" && m.request?.subtype === "can_use_tool") ask?.(m.request_id, m.request.display_name ?? m.request.tool_name, m.request.input);
    if (m.type === "result") { this.ok = !m.is_error && m.subtype !== "error"; this.final ??= parseFinal(m.result ?? ""); }
  }
}

export class CodexConverter {
  started = false; ok: boolean | undefined; session?: string; final?: Final; private cwd: string;
  progress: Progress = { wrote: false, checked: false };
  /** #815: first output line of the last command that succeeded. */
  output?: string;
  constructor(cwd: string) { this.cwd = cwd; }
  feed(line: string, emit: Emit) {
    let m: any; try { m = JSON.parse(line); } catch { return; }
    const it = m.item ?? {};
    if (m.type === "thread.started" && !this.started) { this.started = true; this.session = m.thread_id; emit({ kind: "started", title: "Agent started" }); }
    const fin = m.type === "item.completed" && it.type === "agent_message" ? parseFinal(it.text) : undefined;
    if (fin) { this.final = fin; if (fin.status === "done" && fin.summary) emit({ kind: "message", title: clean(fin.summary, this.cwd, 60), detail: clean(fin.summary, this.cwd, 2000) }); }
    else if (m.type === "item.completed" && it.type === "agent_message" && it.text) emit({ kind: "message", title: clean(it.text, this.cwd, 60), detail: clean(it.text, this.cwd, 2000) });
    if (m.type === "item.started" && it.type === "command_execution") { const cmd = clean(unwrap(it.command ?? ""), this.cwd, 200); emit({ kind: "command_run", title: `Ran ${cmd.slice(0, 50)}`, command: cmd }); }
    if (m.type === "item.completed" && it.type === "command_execution" && it.exit_code === 0) { const l = firstLine(it.aggregated_output); if (l) this.output = l; if (this.progress.wrote) this.progress.checked = true; }
    if (m.type === "item.completed" && it.type === "file_change") for (const ch of it.changes ?? []) { this.progress = { wrote: true, checked: false }; const p = clean(rel(ch.path, this.cwd), this.cwd, 200); emit({ kind: "file_written", title: `Wrote ${p.slice(0, 60)}`, path: p }); }
    if (m.type === "turn.completed") this.ok = true;
    if (m.type === "turn.failed" || m.type === "error") this.ok = false;
  }
}

// ---- process supervision -------------------------------------------------------------------------------------
export function killTree(child: ChildProcess) {
  if (child.exitCode !== null || child.pid === undefined) return;
  killPidTree(child.pid);
}
export function killPidTree(pid: number) {
  if (process.platform === "win32") spawnSync("taskkill", ["/PID", String(pid), "/T", "/F"], { stdio: "ignore", windowsHide: true });
  else try { process.kill(-pid, "SIGKILL"); } catch { try { process.kill(pid, "SIGKILL"); } catch { /* gone */ } }
}
/** Is `pid` still alive AND one of ours (node or an agent CLI)? Guards against killing a recycled pid after a crash. */
export function isAgentPid(pid: number): boolean {
  try { process.kill(pid, 0); } catch { return false; }
  const r = process.platform === "win32"
    ? spawnSync("tasklist", ["/FI", `PID eq ${pid}`, "/FO", "CSV", "/NH"], { encoding: "utf8", windowsHide: true })
    : spawnSync("ps", ["-p", String(pid), "-o", "comm="], { encoding: "utf8" });
  return /node|codex|claude/i.test(r.stdout ?? "");
}

export function agentAvailable(kind: AgentKind): boolean {
  if (kind === "replay") return true;
  const r = spawnSync(kind, ["--version"], { shell: process.platform === "win32", stdio: "ignore", timeout: 15000 });
  return r.status === 0;
}

/** #1474: installed is not enough; a CLI that is not signed in fails every task. Both CLIs exit non-zero from their
 *  own status command when signed out (`codex login status`, `claude auth status`). */
export type AgentReadiness = "ready" | "missing" | "signed_out";
export function agentReadiness(kind: AgentKind): AgentReadiness {
  if (kind === "replay") return "ready";
  if (!agentAvailable(kind)) return "missing";
  const args = kind === "codex" ? ["login", "status"] : ["auth", "status"];
  const r = spawnSync(kind, args, { shell: process.platform === "win32", stdio: "ignore", timeout: 20000, windowsHide: true });
  return r.status === 0 ? "ready" : "signed_out";
}
/** What to tell the person on the computer, one sentence each. */
export function readinessAdvice(r: Partial<Record<"codex" | "claude", AgentReadiness>>): string {
  const ready = Object.entries(r).filter(([, v]) => v === "ready").map(([k]) => k);
  if (ready.length) return `Coding agent ready: ${ready.join(", ")}.`;
  const out = Object.entries(r).filter(([, v]) => v === "signed_out").map(([k]) => k);
  if (out.length) return `Your coding agent is installed but not signed in. Run "${out[0] === "codex" ? "codex login" : "claude"}" in a terminal, sign in, then start the host again.`;
  return `No coding agent found. Install codex or claude, sign in, then start the host again. Pairing and the recorded replay still work.`;
}
/** Agent error text that means "sign in again" (stderr, or the CLI's own error/result line). */
export const SIGNED_OUT_TEXT = /not logged in|please run \/login|run `?codex login|unauthori[sz]ed|\b401\b|invalid api key|authentication (failed|required|error)|login required|token (has )?expired/i;

export interface RunEnd { ok: boolean; exit_code: number | null; final?: Final; session?: string; output?: string }
/** First non-empty line of a command's output (what the program printed first), or undefined. */
export const firstLine = (s: unknown) => String(typeof s === "string" ? s : Array.isArray(s) ? s.map((c: any) => c?.text ?? "").join("\n") : "").split(/\r?\n/).map(l => l.trim()).find(Boolean);
export interface Run {
  child: ChildProcess; done: Promise<RunEnd>; session?: string;
  answer(decisionId: string, optionId: string | undefined, text?: string): boolean; cancel(): void; canceled: boolean; suspended: boolean;
  /** #1052: files written / self-test passed so far, for the host watchdog. */
  progress(): Progress;
  /** #1052: tell a silent agent to finish and report. One CLI takes it on stdin; the other is resumed in the same session with it. */
  nudge(text: string): boolean;
}
export type Ask = (id: string, title: string, options: { id: string; label: string }[], allow_text: boolean) => void;
export interface RunOpts {
  kind: AgentKind; prompt: string; cwd: string; emit: Emit; raw?: (line: string) => void; replayFile?: string; ask: Ask;
  /** Continue an earlier session of the same agent (its documented resume) instead of starting a new one. */
  resume?: string; schemaFile?: string;
  /** Called once the agent names its session, so a crash right after still leaves something to resume. */
  onSession?: (id: string) => void;
}

/** The closing-answer schema the host asks for (one CLI enforces it via --output-schema). */
export const FINAL_SCHEMA = {
  type: "object", additionalProperties: false, required: ["status", "summary", "question", "options", "parts"],
  properties: { status: { type: "string", enum: ["done", "question"] }, summary: { type: "string" }, question: { type: "string" }, options: { type: "array", items: { type: "string" } },
    // #972: the plan the headset draws before any code exists; [] on every other answer.
    parts: { type: "array", items: { type: "object", additionalProperties: false, required: ["id", "name", "files"],
      properties: { id: { type: "string" }, name: { type: "string" }, files: { type: "array", items: { type: "string" } } } } } },
};
const quote = (a: string) => process.platform === "win32" && /[\s"&|<>^]/.test(a) ? `"${a}"` : a;

export function startRun(o: RunOpts): Run {
  const win = process.platform === "win32";
  let child: ChildProcess;
  const pending = new Map<string, { tool: string; input: any; options: { id: string; label: string }[] }>(); // live questions
  const write = (obj: any) => { if (child.stdin?.writable) child.stdin.write(JSON.stringify(obj) + "\n"); };
  const codexSpawn = (resume: string | undefined, prompt: string) => {
    // Prompt goes on stdin, so no user text ever reaches a shell command line (the CLI is a .cmd shim on Windows).
    // The sandbox keeps the agent inside its worktree (writes elsewhere, including the repo's .git, are refused).
    const common = ["--json", "--skip-git-repo-check", "-c", "sandbox_mode=workspace-write", ...(o.schemaFile ? ["--output-schema", quote(o.schemaFile)] : [])];
    const args = resume ? ["exec", "resume", ...common, resume, "-"] : ["exec", ...common, "-"];
    const c = spawn("codex", args, { cwd: o.cwd, stdio: ["pipe", "pipe", "pipe"], shell: win, detached: !win, windowsHide: true });
    c.stdin!.end(prompt);
    return c;
  };
  if (o.kind === "claude") {
    child = spawn("claude", ["-p", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose",
      "--permission-prompt-tool", "stdio", "--permission-mode", "acceptEdits", ...(o.resume ? ["--resume", o.resume] : [])],
      { cwd: o.cwd, stdio: ["pipe", "pipe", "pipe"], detached: !win, windowsHide: true });
    write({ type: "control_request", request_id: "init_1", request: { subtype: "initialize" } });
    write({ type: "user", message: { role: "user", content: o.prompt }, parent_tool_use_id: null, session_id: "" });
  } else if (o.kind === "codex") {
    child = codexSpawn(o.resume, o.prompt);
  } else {
    child = spawn(process.execPath, ["--experimental-strip-types", "--no-warnings", fileURLToPath(new URL("../test/fake_agent.ts", import.meta.url)), o.replayFile!],
      { cwd: o.cwd, stdio: ["pipe", "pipe", "pipe"], detached: !win, windowsHide: true });
    child.stdin!.write(JSON.stringify({ prompt: o.prompt, resume: o.resume ?? null }) + "\n");
  }
  const isCodex = o.kind === "codex" || (o.kind === "replay" && /codex/.test(o.replayFile ?? ""));
  const conv = isCodex ? new CodexConverter(o.cwd) : new ClaudeConverter(o.cwd);
  let finish!: (e: RunEnd) => void;
  const run: Run = {
    child, canceled: false, suspended: false,
    done: new Promise(res => { finish = res; }),
    progress: () => ({ ...conv.progress }),
    nudge(text) {
      if (child.exitCode !== null) return false;
      if (o.kind !== "codex") {
        if (!child.stdin?.writable) return false;
        if (o.kind === "claude") write({ type: "user", message: { role: "user", content: text }, parent_tool_use_id: null, session_id: conv.session ?? "" });
        else child.stdin.write(JSON.stringify({ nudge: text }) + "\n");
        return true;
      }
      // codex reads its prompt once and closes stdin: stop the silent process and resume the same session with the nudge.
      const session = conv.session ?? o.resume; if (!session) return false;
      const old = child; child = run.child = codexSpawn(session, text); wire(child); killTree(old);
      return true;
    },
    answer(id, opt, text) {
      const req = pending.get(id); if (!req) return false;
      const label = req.options.find(x => x.id === opt)?.label;
      if (req.tool !== "AskUserQuestion" && !["allow", "deny"].includes(opt ?? "")) return false;
      pending.delete(id);
      if (o.kind === "replay") { child.stdin?.write(JSON.stringify({ answer: id, opt: opt ?? "text", text }) + "\n"); return true; }
      let response: any;
      if (req.tool === "AskUserQuestion") {
        const first = req.input?.questions?.[0]?.question ?? "";
        response = { behavior: "allow", updatedInput: { ...req.input, answers: { [first]: [label, text].filter(Boolean).join(". ") } } };
      } else response = opt === "allow" ? { behavior: "allow", updatedInput: req.input } : { behavior: "deny", message: text || "The person said no." };
      write({ type: "control_response", response: { subtype: "success", request_id: id, response } });
      return true;
    },
    cancel() { run.canceled = true; killTree(child); },
  };
  const wire = (me: ChildProcess) => {
  // A child replaced by a nudge (codex) closes without ending the run; only the current child's close ends it.
  me.on("close", code => { if (me === child) finish({ ok: !run.canceled && code === 0 && conv.ok !== false, exit_code: code, final: conv.final, session: conv.session ?? o.resume, output: conv.output }); });
  me.on("error", () => {});
  me.stderr?.on("data", d => o.raw?.(`[stderr] ${String(d).trimEnd()}`));
  createInterface({ input: me.stdout! }).on("line", line => {
    if (me !== child) return;
    o.raw?.(line);
    conv.feed(line, o.emit, (id: string, tool: string, input: any) => {
      if (tool === "AskUserQuestion") {
        // The agent's own multiple-choice question: the headset shows its options; a spoken answer can be free text.
        const qq = input?.questions?.[0] ?? {};
        const options = (qq.options ?? []).slice(0, 6).map((x: any, i: number) => ({ id: `o${i + 1}`, label: clean(x.label ?? String(x), o.cwd, 60) }));
        pending.set(id, { tool, input, options });
        return o.ask(id, clean(qq.question ?? "The agent has a question", o.cwd, 160), options.length ? options : [{ id: "o1", label: "Go ahead" }], true);
      }
      const options = [{ id: "allow", label: "Yes" }, { id: "deny", label: "No" }];
      pending.set(id, { tool, input, options });
      const what = clean(input?.command ?? input?.file_path ?? input?.url ?? "", o.cwd, 80);
      // Tool names are product names (shells, editors): the headset only ever sees a generic verb.
      const verb = /bash|shell|powershell|cmd/i.test(tool) ? "running" : /write|edit/i.test(tool) ? "changing" : /fetch|web/i.test(tool) ? "opening" : "this step";
      o.ask(id, clean(`Allow ${verb}${what ? ` ${what}` : ""}?`, o.cwd, 120), options, false);
    });
    if (conv.session && run.session !== conv.session) { run.session = conv.session; o.onSession?.(conv.session); }
    let m: any; try { m = JSON.parse(line); } catch { return; }
    if (o.kind === "claude" && m.type === "result") child.stdin?.end();
  });
  };
  wire(child);
  return run;
}
