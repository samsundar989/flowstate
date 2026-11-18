// Claude Code -> agent events. One Converter per agent; fed either by `claude -p --output-format stream-json --verbose`
// lines (feedStreamJson) or by HTTP hook payloads (feedHook). Both paths share the same step bookkeeping.
import { spawn, type ChildProcess } from "node:child_process";
import { createInterface } from "node:readline";
import { homedir, userInfo } from "node:os";
import type { AgentEvent } from "./schema.ts";

const MAX_DETAIL = 280;

export class Converter {
  agent_id: string; agent_name: string; run_id?: string;
  private t0: number;
  private root: string;
  private started = false;
  private finished = false;
  private steps = new Map<string, { sid: string; tool: string; key: string }>(); // tool_use_id -> step
  private failedByKey = new Map<string, string>(); // tool+input -> failed step id (retry detection)
  private frontier = new Map<string, { msg: string; ids: string[]; prev: string[] }>(); // parent ctx -> current batch
  private cwdRoots: string[];
  private homeRoots: string[];
  private extraRedact: string[];
  private userRe: RegExp;

  constructor(agent_id: string, opts: { agent_name?: string; t0?: number; run_id?: string; cwd?: string; redact?: string[] } = {}) {
    this.agent_id = agent_id;
    this.agent_name = opts.agent_name ?? agent_id[0].toUpperCase() + agent_id.slice(1);
    this.t0 = opts.t0 ?? Date.now();
    this.run_id = opts.run_id;
    this.root = `${agent_id}.root`;
    // JSON-escaped (\\), native (\) and forward-slash spellings; escaped first so it is not half-replaced.
    const variants = (p?: string) => (p ? [p.replace(/\\/g, "\\\\"), p, p.replace(/\\/g, "/"), p.replace(/^([A-Za-z]):\\/, (_m, d) => `/${d.toLowerCase()}/`).replace(/\\/g, "/")] : []);
    this.cwdRoots = variants(opts.cwd);
    this.homeRoots = variants(homedir());
    this.extraRedact = opts.redact ?? [];
    this.userRe = new RegExp(String.raw`\b${userInfo().username.replace(/[^A-Za-z0-9_]/g, "")}\b`, "gi");
  }

  /** Strip local paths and anything key-shaped; collapse whitespace; truncate. */
  redact(s: string): string {
    let out = s;
    for (const r of this.cwdRoots) out = out.split(r).join(".");
    for (const r of this.homeRoots) out = out.split(r).join("~");
    for (const r of this.extraRedact) out = out.split(r).join("[redacted]");
    out = out.replace(this.userRe, "user");
    out = out.replace(/\/tmp\/claude\/[^\s"'\\]*/g, "."); // Claude Code sandbox temp paths
    out = out.replace(/\b(sk-ant-[A-Za-z0-9_-]+|sk-[A-Za-z0-9]{20,}|gh[pousr]_[A-Za-z0-9]{20,}|AKIA[0-9A-Z]{16})\b/g, "[redacted]");
    out = out.replace(/\s+/g, " ").trim();
    return out.length > MAX_DETAIL ? out.slice(0, MAX_DETAIL - 1) + "…" : out;
  }

  private ev(e: Omit<AgentEvent, "t_ms" | "agent_id">, at?: number): AgentEvent {
    return { t_ms: Math.max(0, Math.round((at ?? Date.now()) - this.t0)), ...(this.run_id ? { run_id: this.run_id } : {}), agent_id: this.agent_id, ...e } as AgentEvent;
  }

  begin(task: string, at?: number): AgentEvent[] {
    if (this.started) return [];
    this.started = true;
    return [
      this.ev({ agent_name: this.agent_name, kind: "task_assigned", title: this.redact(task).slice(0, 120), detail: this.redact(task), priority: "normal", status: "SUBMITTED" }, at),
      this.ev({ kind: "step_start", step_id: this.root, parent_step_id: null, title: this.redact(task).slice(0, 60) }, at),
      this.ev({ kind: "status", status: "WORKING", step_id: this.root, title: "Working" }, at),
    ];
  }

  toolStart(id: string, name: string, input: any, parentToolId: string | null, msgId: string, at?: number): AgentEvent[] {
    const out = this.begin("(task)", at);
    if (name === "TodoWrite" && Array.isArray(input?.todos)) {
      return [...out, this.ev({ kind: "plan", step_id: this.parentStep(parentToolId), title: "Plan", plan: input.todos.map((t: any) => this.redact(`${t.status === "completed" ? "[x] " : ""}${t.content ?? t.subject ?? ""}`)) }, at)];
    }
    const parent = this.parentStep(parentToolId);
    const ctx = this.frontier.get(parent) ?? { msg: "", ids: [], prev: [] };
    if (ctx.msg !== msgId) { ctx.prev = ctx.ids.length ? ctx.ids : ctx.prev; ctx.ids = []; ctx.msg = msgId; }
    const sid = `${this.agent_id}.${id.slice(-8)}`;
    const summary = this.summarise(name, input);
    const key = `${name}|${summary}`;
    const retryOf = this.failedByKey.get(key);
    ctx.ids.push(sid);
    this.frontier.set(parent, ctx);
    this.steps.set(id, { sid, tool: name, key });
    return [
      ...out,
      this.ev({
        kind: "step_start", step_id: sid, parent_step_id: parent, title: `${name} ${summary}`.slice(0, 60),
        ...(ctx.prev.length ? { depends_on: [...ctx.prev] } : {}), ...(retryOf ? { retry_of: retryOf, attempt: 2 } : {}),
      }, at),
      this.ev({ kind: "tool_call", step_id: sid, tool: name, title: name, detail: this.redact(typeof input === "string" ? input : JSON.stringify(input ?? {})) }, at),
    ];
  }

  toolEnd(id: string, content: unknown, isError: boolean, at?: number): AgentEvent[] {
    const s = this.steps.get(id);
    if (!s) return [];
    this.steps.delete(id);
    if (isError) this.failedByKey.set(s.key, s.sid); else this.failedByKey.delete(s.key);
    const text = Array.isArray(content) ? content.map((c: any) => c?.text ?? "").join(" ") : typeof content === "string" ? content : JSON.stringify(content ?? "");
    const st = isError ? "FAILED" : "COMPLETED";
    const out = [
      this.ev({ kind: "tool_result", step_id: s.sid, tool: s.tool, status: st, title: `${s.tool} ${isError ? "failed" : "ok"}`, detail: this.redact(text) }, at),
      this.ev({ kind: "step_end", step_id: s.sid, status: st, title: s.tool }, at),
    ];
    if (isError) out.push(this.ev({ kind: "error", step_id: s.sid, title: `${s.tool} failed`, detail: this.redact(text) }, at));
    return out;
  }

  text(t: string, parentToolId: string | null, at?: number): AgentEvent[] {
    const r = this.redact(t);
    if (!r) return [];
    return [...this.begin("(task)", at), this.ev({ kind: "message", step_id: this.parentStep(parentToolId), title: r.split(/(?<=[.!?:])\s/)[0].slice(0, 60), detail: r }, at)];
  }

  finish(ok: boolean, summary: string, at?: number): AgentEvent[] {
    if (this.finished) return [];
    this.finished = true;
    const out: AgentEvent[] = [];
    for (const id of [...this.steps.keys()]) out.push(...this.toolEnd(id, "interrupted", true, at)); // close dangling steps
    const st = ok ? "COMPLETED" : "FAILED";
    out.push(this.ev({ kind: "step_end", step_id: this.root, status: st, title: "Run" }, at));
    out.push(ok ? this.ev({ kind: "done", step_id: this.root, status: st, title: "Done", detail: this.redact(summary) }, at)
                : this.ev({ kind: "error", step_id: this.root, title: "Run failed", detail: this.redact(summary) }, at));
    out.push(this.ev({ kind: "status", status: st, title: ok ? "Completed" : "Failed" }, at));
    return out;
  }

  private parentStep(parentToolId: string | null): string {
    return (parentToolId && this.steps.get(parentToolId)?.sid) || this.root;
  }

  private summarise(name: string, input: any): string {
    const i = input ?? {};
    const v = i.file_path ?? i.path ?? i.command ?? i.pattern ?? i.url ?? i.query ?? i.description ?? i.prompt ?? "";
    let s = this.redact(String(v));
    if (i.file_path || i.path) s = s.replace(/^.*[\\/]/, "");
    return s.slice(0, 50);
  }

  // --- stream-json --------------------------------------------------------
  feedStreamJson(line: string, task = "(task)"): AgentEvent[] {
    let d: any;
    try { d = JSON.parse(line); } catch { return []; }
    const at = d.timestamp ? Date.parse(d.timestamp) : Date.now();
    if (d.type === "system" && d.subtype === "init") return this.begin(task, at);
    const out: AgentEvent[] = [];
    const parent = d.parent_tool_use_id ?? null;
    if (d.type === "assistant") {
      for (const c of d.message?.content ?? []) {
        if (c.type === "text") out.push(...this.text(c.text, parent, at));
        else if (c.type === "tool_use") out.push(...this.toolStart(c.id, c.name, c.input, parent, d.message?.id ?? d.uuid, at));
      }
    } else if (d.type === "user") {
      for (const c of d.message?.content ?? []) if (c.type === "tool_result") out.push(...this.toolEnd(c.tool_use_id, c.content, !!c.is_error, at));
    } else if (d.type === "result") {
      out.push(...this.finish(d.subtype === "success" && !d.is_error, d.result ?? d.subtype ?? ""));
    }
    return out;
  }

  // --- HTTP hooks (https://code.claude.com/docs/en/hooks.md) ------------------
  feedHook(h: any): AgentEvent[] {
    const parent = null;
    switch (h.hook_event_name) {
      case "SessionStart": return this.begin(h.source ? `session ${h.source}` : "(session)");
      case "UserPromptSubmit": return this.begin(h.prompt ?? "(prompt)");
      case "PreToolUse": return this.toolStart(h.tool_use_id ?? `${Date.now()}`, h.tool_name, h.tool_input, parent, `hook-${h.tool_use_id}`);
      case "PostToolUse": return this.toolEnd(h.tool_use_id, h.tool_response?.stdout ?? h.tool_response ?? "", false);
      case "PostToolUseFailure": return this.toolEnd(h.tool_use_id, h.error ?? h.tool_response ?? "error", true);
      case "Notification": return [...this.begin("(session)"), this.ev({ kind: "message", step_id: this.root, title: this.redact(h.message ?? "Notification").slice(0, 60), detail: this.redact(h.message ?? "") })];
      case "Stop": return this.finish(true, h.last_assistant_message ?? "stopped");
      default: return [];
    }
  }
}

export interface ClaudeRun { child: ChildProcess; done: Promise<number> }

/** Spawn `claude -p <task> --output-format stream-json --verbose` and stream converted events to onEvent. */
export function runClaude(task: string, conv: Converter, onEvent: (e: AgentEvent) => void, opts: { cwd: string; model?: string; extraArgs?: string[]; rawLog?: (l: string) => void }): ClaudeRun {
  const args = ["-p", task, "--output-format", "stream-json", "--verbose", ...(opts.model ? ["--model", opts.model] : []), ...(opts.extraArgs ?? [])];
  const child = spawn("claude", args, { cwd: opts.cwd, stdio: ["ignore", "pipe", "pipe"] });
  for (const e of conv.begin(task)) onEvent(e);
  const rl = createInterface({ input: child.stdout! });
  rl.on("line", (l) => { opts.rawLog?.(l); for (const e of conv.feedStreamJson(l, task)) onEvent(e); });
  child.stderr!.on("data", (b) => process.stderr.write(`[${conv.agent_id}] ${b}`));
  const done = new Promise<number>((res) => child.on("close", (code) => {
    for (const e of conv.finish(code === 0, `claude exited with code ${code}`)) onEvent(e);
    res(code ?? 1);
  }));
  return { child, done };
}
