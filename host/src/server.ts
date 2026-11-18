// The host: one TLS WebSocket port on the LAN address, pairing, resumable events, and the job runner:
// projects, a task queue, a git worktree per task, agent runs, results merged into main, questions to the human and back.
// State machine: src/state.ts. Every state change is an event in events.jsonl, so a restart rebuilds it.
import { createServer, type Server } from "node:https";
import { createHash, randomBytes } from "node:crypto";
import { mkdirSync, existsSync, appendFileSync, readFileSync, writeFileSync, renameSync, rmSync } from "node:fs";
import { join } from "node:path";
import { WebSocketServer, type WebSocket } from "ws";
import { loadIdentity, DeviceStore, type Identity, type Device } from "./config.ts";
import { Pairing } from "./pairing.ts";
import { PairConfirm, type ConfirmConn } from "./pairing_confirm.ts";
import { EventLog, type NewEvent } from "./eventlog.ts";
import { AUTH_TIMEOUT_MS, CHUNK_BYTES, MAX_FILE_BYTES, PART_NAME_MAX, SAFE_PATH, SAY_MAX, checkCode, validate, type ErrorCode, type HostEvent, type Part } from "./protocol.ts";
import { startRun, agentReadiness, SIGNED_OUT_TEXT, clean, killPidTree, isAgentPid, FINAL_SCHEMA, type AgentKind, type Run, type RawPart } from "./agents.ts";
import { HostState, canMove, type TaskState, type TaskView } from "./state.ts";
import { initProject, ensureWorktree, commitIfDirty, mergeTask, removeWorktree, branchOf, worktreeRel, fileAtCommit } from "./git.ts";

export interface HostOpts {
  bind: string; port: number; dir: string; projectsRoot: string;
  agent?: AgentKind | "auto"; replayFile?: string; now?: () => number; log?: (s: string) => void;
  /** Limits a stranger needs: agents per project (default 1), agents in total (default 2), wall clock per run (default 30 min). */
  perProject?: number; maxRunning?: number; taskTimeoutMs?: number;
  /** #1052 watchdog: a running task that wrote files (or passed its self-test) and then emits nothing for `quiet` ms is nudged once;
   *  still silent `grace` ms later it ends (stalled_done if its self-test passed, else stalled). Defaults 120 s, 60 s, checked every 5 s. */
  watchdogQuietMs?: number; watchdogGraceMs?: number; watchdogTickMs?: number;
}
export const NUDGE = `You have written the files. If your check passed, stop now: make your final message only the closing JSON ({"status":"done",...}). ` +
  `Do not start new work.`;
/** #1035: what an agent receives when the wearer talks to it. Its task instructions (and closing-answer rules) stay as they were. */
export function talkPrompt(said: string): string {
  return `The person is talking to you from the headset while you work. They said: "${said}"
` +
    `Reply to them first, in one or two short plain sentences (no JSON, no code), then continue your task as before. ` +
    `Your final message rules are unchanged.`;
}

/** Host-only facts about a task (never sent to the headset): agent, session to resume, process id, open question. */
interface TaskMeta {
  task_id: string; project_id: string; prompt: string; agent: AgentKind; replay?: string; session?: string; pid?: number;
  restarted?: boolean; question?: { decision_id: string; text: string; options: { id: string; label: string }[]; live: boolean };
  answer?: string; asks: number;
  /** #972: "plan" = a new project's first task (plan and one design question, then build); "followup" = a later task on it. */
  phase?: "plan" | "followup";
}

/** #972 project shape, pinned for every project the headset builds from nothing (docs/host/PROTOCOL.md section 7). */
const WEB_SHAPE = `Build it as a static web app: the entry file is index.html; at most 3 files in total (for example index.html, style.css, app.js); ` +
  `no build step, no package manager, no network access, no CDN and no external URL of any kind (no fonts, scripts, images or requests from the web), ` +
  `so it works offline when index.html is opened from a folder. It is shown in a 1280x800 panel: fit that size without scrolling. ` +
  `Input is tap or click only (no keyboard, no hover, no drag). Visible text must not name any company, product or brand.`;
const PLAN_ASK = `Prepare before asking: build the smallest complete implementation now, with the design choice isolated in one setting. ` +
  `Run a focused check of both possible answers now. Keep it to one index.html when practical; add no unrequested features or test files. ` +
  `Do not choose for the person, commit, or report done. Once the implementation and both branches are checked, make your final message only ` +
  `{"status":"question","summary":"<one sentence: what is prepared>","question":"<ONE design question for the person>","options":["<2 or 3 short choices, 3 words or fewer each>"],` +
  `"parts":[<3 to 6 of {"id":"<short lowercase id>","name":"<what the person sees, 24 characters or fewer>","files":["<which of your files hold this part>"]}>]}. ` +
  `You will be resumed with the answer. Then only set the prepared choice, run its focused check, and return done immediately; do not redesign or add features.`;
const sha256 = (b: Buffer) => createHash("sha256").update(b).digest("hex");

const MESSAGES: Record<ErrorCode, string> = {
  bad_request: "That request was not understood.", unauthorized: "Pair with this computer first.",
  bad_token: "That pairing code is not valid.", token_expired: "That pairing code has expired. Show a new one.",
  token_used: "That pairing code was already used.", bad_code: "Wrong code. Check the six digits on the computer.",
  code_expired: "That code has expired. Use the new one on the computer.", rate_limited: "Too many tries. Wait a minute.",
  unknown_project: "No such project.", unknown_decision: "That question is no longer open.", busy: "That project is already working.",
  agent_unavailable: "No coding agent is ready on the computer. Install one there and sign in, then try again.",
  agent_signed_out: "Your coding agent on the computer is not signed in. Sign in there, then try again.", internal: "Something went wrong on the computer.",
  bad_path: "That file name is not allowed.", not_found: "That file is not in that version of the project.", too_large: "That file is too big to send.",
};
export const LOCAL_DEVICE = "d_local";

export class Host {
  id!: Identity; pairing: Pairing; devices: DeviceStore; log: EventLog; state: HostState;
  confirm = new PairConfirm(this); // pairing by confirming six digits on the computer
  runs = new Map<string, Run>(); // task_id -> live agent process
  private https!: Server; private wss!: WebSocketServer;
  private say: (s: string) => void;
  o: HostOpts;
  constructor(o: HostOpts) {
    this.o = o;
    for (const d of [o.dir, o.projectsRoot, join(o.dir, "raw"), join(o.dir, "tasks")]) mkdirSync(d, { recursive: true });
    this.pairing = new Pairing(o.now); this.devices = new DeviceStore(o.dir); this.log = new EventLog(join(o.dir, "events.jsonl"));
    this.state = HostState.from(this.log.events);
    this.say = o.log ?? (s => console.log(s));
    writeFileSync(join(o.dir, "final_schema.json"), JSON.stringify(FINAL_SCHEMA));
  }

  async start(): Promise<{ address: string; port: number }> {
    this.id = await loadIdentity(this.o.dir);
    this.ensureLocalDevice();
    this.https = createServer({ key: this.id.key, cert: this.id.cert, minVersion: "TLSv1.2" });
    this.wss = new WebSocketServer({ server: this.https, path: "/v1", maxPayload: 64 * 1024 });
    this.wss.on("connection", (ws, req) => this.onConnection(ws, req.socket.remoteAddress ?? "?"));
    await new Promise<void>((res, rej) => { this.https.once("error", rej); this.https.listen(this.o.port, this.o.bind, () => res()); });
    const a = this.https.address() as any;
    writeFileSync(join(this.o.dir, "host.json"), JSON.stringify({ pid: process.pid, address: a.address, port: a.port, fp: this.id.fp, started_at: new Date().toISOString() }));
    this.recover();
    return { address: a.address, port: a.port };
  }
  /** Stop the host. Running agents are killed but their tasks are NOT marked finished: the next start puts them back in line. */
  async stop() {
    for (const r of this.runs.values()) { r.suspended = true; r.cancel(); }
    for (const c of this.wss?.clients ?? []) c.terminate();
    await new Promise<void>(r => this.wss ? this.wss.close(() => this.https.close(() => r())) : r());
    try { rmSync(join(this.o.dir, "host.json")); } catch { /* already gone */ }
  }

  /** The command line is a paired device too (same code path as the headset); its secret is in the owner-only config folder. */
  private ensureLocalDevice() {
    const f = join(this.o.dir, "cli.secret");
    if (!existsSync(f)) writeFileSync(f, randomBytes(32).toString("base64url"), { mode: 0o600 });
    const sha = createHash("sha256").update(readFileSync(f, "utf8").trim()).digest("hex");
    const d = this.devices.find(LOCAL_DEVICE);
    if (!d || d.secret_sha256 !== sha) {
      this.devices.devices = this.devices.devices.filter(x => x.device_id !== LOCAL_DEVICE);
      this.devices.add({ device_id: LOCAL_DEVICE, secret_sha256: sha, name: "this computer", paired_at: new Date().toISOString(), from: "local" });
    }
  }

  // ---- connection: pairing and resume (unchanged from the skeleton) ------------------------------------------
  private onConnection(ws: WebSocket, from: string) {
    let device: Device | undefined, unfollow: (() => void) | undefined;
    const send = (f: object) => { if (ws.readyState === ws.OPEN) ws.send(JSON.stringify(f)); };
    const fail = (code: ErrorCode, req_id?: string, close = false) => { send({ type: "error", code, message: MESSAGES[code], ...(req_id ? { req_id } : {}) }); if (close) ws.close(4401, code); };
    const authTimer = setTimeout(() => { if (!device) ws.close(4401, "auth timeout"); }, AUTH_TIMEOUT_MS);
    const ping = setInterval(() => { if (ws.readyState === ws.OPEN) ws.ping(); }, 15_000);
    ws.on("close", () => { clearTimeout(authTimer); clearInterval(ping); unfollow?.(); });
    const cc: ConfirmConn = { send, close: (code, why) => ws.close(code, why), admit: d => { device = d; } };
    ws.on("close", () => this.confirm.closed(cc));
    ws.on("message", (data, isBinary) => {
      let f: any; try { f = JSON.parse(String(data)); } catch { fail("bad_request"); return ws.close(4400, "bad json"); }
      const bad = isBinary ? "binary" : validate(f, "c2h");
      if (bad) { fail("bad_request", f?.req_id); if (!device) ws.close(4400, bad); return; }
      if (!device) {
        if (f.type === "pair_token" || f.type === "pair_code") {
          const secret = f.type === "pair_token" ? f.token : f.code;
          const err = f.type === "pair_token" ? this.pairing.useToken(f.token, from) : this.pairing.useCode(f.code, from);
          if (err) { this.say(`pairing refused from ${from}: ${err}`); return fail(err, undefined, true); }
          const device_secret = randomBytes(32).toString("base64url");
          device = { device_id: "d_" + randomBytes(6).toString("hex"), secret_sha256: createHash("sha256").update(device_secret).digest("hex"),
            name: String(f.device_name).slice(0, 40), paired_at: new Date().toISOString(), from };
          this.devices.add(device);
          const check = checkCode(this.id.fp, secret);
          this.say(`PAIRED device "${device.name}" from ${from} (check ${check}). If you did not just pair, remove it from devices.json.`);
          this.pairing.rotate();
          return send({ type: "paired", device_id: device.device_id, device_secret, host_name: this.id.name, check });
        }
        if (f.type === "resume") {
          const d = this.devices.find(f.device_id);
          const ok = d && d.secret_sha256 === createHash("sha256").update(f.device_secret).digest("hex");
          if (!ok) return fail("unauthorized", undefined, true);
          device = d;
          send({ type: "welcome", host_name: this.id.name, head_seq: this.log.head, open_decisions: [...this.state.decisions.keys()], projects: this.state.summary() });
          unfollow = this.log.follow(f.last_seq, e => send(e));
          return;
        }
        if (f.type === "pair_request" || f.type === "pair_nonce") { clearTimeout(authTimer); return this.confirm.frame(cc, f, from); }
        return fail("unauthorized", f.req_id, true);
      }
      try { this.handle(f, (code, req_id) => fail(code, req_id), send); }
      catch (e) { this.say(`internal error: ${(e as Error).stack}`); fail("internal", f.req_id); }
    });
  }

  // ---- requests: the headset and the command line both land here ------------------------------------------------
  private handle(f: any, fail: (c: ErrorCode, req_id?: string) => void, send: (f: object) => void) {
    const ok = (extra: object) => send({ type: "ok", req_id: f.req_id, ...extra });
    if (f.type === "create_project") {
      // A headset that lost the `ok` (Wi-Fi drop) resends with the same req_id: answer it, do not start a second project.
      const seen = this.state.created.get(f.req_id);
      if (seen?.task_id) return ok({ project_id: seen.project_id, task_id: seen.task_id });
      const kind = this.pickAgent(f.agent);
      const p = this.createProject(f.name ?? f.prompt, f.req_id);
      // #1474: no ready agent is a task that ends at once with the next action, so the headset's project window says it
      // (an error frame for a create is not shown there; it would wait in silence).
      const t = kind === "!missing" || kind === "!signed_out" ? this.notReady(p, f.prompt, kind, f.req_id) : this.enqueue(p, f.prompt, kind, f.req_id);
      ok({ project_id: p, task_id: t });
      return this.pump();
    }
    if (f.type === "prompt") {
      if (!this.state.projects.has(f.project_id)) return fail("unknown_project", f.req_id);
      const kind = this.pickAgent("auto");
      const t = kind === "!missing" || kind === "!signed_out" ? this.notReady(f.project_id, f.text, kind) : this.enqueue(f.project_id, f.text, kind);
      ok({ project_id: f.project_id, task_id: t });
      return this.pump();
    }
    if (f.type === "answer") {
      const d = this.state.decisions.get(f.decision_id);
      const opt = d?.options.find(o => o.id === f.option_id);
      if (!d || (!opt && !(f.text && d.allow_text))) return fail("unknown_decision", f.req_id);
      const t = d.task_id ? this.state.tasks.get(d.task_id) : undefined;
      const run = t ? this.runs.get(t.task_id) : undefined;
      if (run) { if (!run.answer(f.decision_id, f.option_id, f.text)) return fail("unknown_decision", f.req_id); }
      else if (!t || !this.meta(t.task_id).session) return fail("unknown_decision", f.req_id);
      ok({ project_id: d.project_id, ...(t ? { task_id: t.task_id } : {}) });
      const said = [opt?.label, f.text].filter(Boolean).join(". ");
      this.emit({ project_id: d.project_id, task_id: t?.task_id, run_id: d.run_id, kind: "answered", title: clean(`You said: ${said}`, "", 120),
        decision_id: f.decision_id, ...(f.option_id ? { option_id: f.option_id } : {}), ...(f.text ? { text: clean(f.text, "", 2000) } : {}) });
      if (!t) return;
      if (run) return this.setState(t, "running", "Working");
      // The agent ended its turn with the question: resume the same session with the answer.
      const m = this.meta(t.task_id); m.answer = `The person answered your question ("${m.question?.text ?? d.title}"): ${said}`; delete m.question; this.saveMeta(m);
      return this.startTask(t);
    }
    if (f.type === "say") {
      // #1035: a sentence spoken at one agent on the site goes into that task's own running session (its documented
      // input: stdin, or a resume of the same session) and nowhere else. No live run: refused, and the headset says so.
      if (!this.state.projects.has(f.project_id)) return fail("unknown_project", f.req_id);
      const t = this.state.tasks.get(f.task_id);
      const run = t && t.project_id === f.project_id ? this.runs.get(t.task_id) : undefined;
      if (!t || !run || run.canceled) return fail("not_found", f.req_id);
      const said = clean(f.text, "", SAY_MAX);
      if (!run.nudge(talkPrompt(said))) return fail("busy", f.req_id);
      this.say(`host talk task=${t.task_id} chars=${said.length}`);
      ok({ project_id: t.project_id, task_id: t.task_id });
      this.emit({ project_id: t.project_id, task_id: t.task_id, kind: "answered", title: clean(`You said to the agent: ${said}`, "", 120), text: said, req_id: f.req_id });
      return;
    }
    if (f.type === "get_file") {
      // #972: one chunk of one file tracked at one commit on the project's main; never the working folder, never outside it.
      if (!this.state.projects.has(f.project_id)) return fail("unknown_project", f.req_id);
      const r = fileAtCommit(this.projectDir(f.project_id), f.commit, f.path, MAX_FILE_BYTES, SAFE_PATH);
      if (!r.ok) return fail(r.code, f.req_id);
      const total = r.bytes.length;
      if (f.offset > total || (f.offset === total && total > 0)) return fail("bad_request", f.req_id);
      return send({ type: "file_chunk", req_id: f.req_id, path: f.path, sha256: sha256(r.bytes), offset: f.offset, total,
        data: r.bytes.subarray(f.offset, f.offset + CHUNK_BYTES).toString("base64") });
    }
    if (f.type === "cancel") {
      if (!this.state.projects.has(f.project_id)) return fail("unknown_project", f.req_id);
      const open = [...this.state.tasks.values()].filter(t => t.project_id === f.project_id && !["done", "failed", "cancelled"].includes(t.state));
      const t = f.task_id ? open.find(x => x.task_id === f.task_id) : (this.state.active(f.project_id)[0] ?? open[0]);
      if (!t) return fail("unknown_project", f.req_id);
      ok({ project_id: f.project_id, task_id: t.task_id });
      return this.cancelTask(t);
    }
    fail("bad_request", f.req_id);
  }

  /** An agent that is installed AND signed in; otherwise "!missing" or "!signed_out" (#1474: say which). */
  private pickAgent(want?: string): AgentKind | "!missing" | "!signed_out" {
    const k = (want && want !== "auto" ? want : this.o.agent ?? "auto") as AgentKind | "auto";
    const kinds = (k !== "auto" ? (["claude", "codex", "replay"].includes(k) ? [k] : []) : ["codex", "claude"]) as AgentKind[];
    let seen: "!missing" | "!signed_out" = "!missing";
    for (const x of kinds) { const r = agentReadiness(x); if (r === "ready") return x; if (r === "signed_out") seen = "!signed_out"; }
    this.say(seen === "!signed_out" ? "A coding agent is installed but not signed in. Sign in to it in a terminal on this computer, then try again from the headset."
      : "No coding agent is installed. Install codex or claude and sign in, then try again from the headset.");
    return seen;
  }

  // ---- the job runner ----------------------------------------------------------------------------------------------
  private emit(e: NewEvent): HostEvent {
    if (e.kind === "file_written" && e.path && !e.part_id) {
      const part = this.state.projects.get(e.project_id)?.parts?.find(p => p.files.includes(e.path!));
      if (part) e = { ...e, part_id: part.id };
    }
    const tidy = Object.fromEntries(Object.entries(e).filter(([, v]) => v !== undefined)) as NewEvent;
    const ev = this.log.append(tidy); this.state.apply(ev); return ev;
  }
  private setState(t: TaskView, to: TaskState, title: string, extra: Partial<NewEvent> = {}) {
    if (!canMove(t.state, to)) { this.say(`ignored ${t.task_id}: ${t.state} -> ${to}`); return; }
    this.emit({ project_id: t.project_id, task_id: t.task_id, kind: "task_state", state: to, title, ...extra });
  }
  private meta(task_id: string): TaskMeta { return JSON.parse(readFileSync(join(this.o.dir, "tasks", `${task_id}.json`), "utf8")); }
  private saveMeta(m: TaskMeta) { const f = join(this.o.dir, "tasks", `${m.task_id}.json`); writeFileSync(f + ".tmp", JSON.stringify(m, null, 1)); renameSync(f + ".tmp", f); }
  projectDir(project_id: string) { return join(this.o.projectsRoot, project_id.replace(/^p_/, "")); }

  createProject(nameOrPrompt: string, req_id?: string): string {
    const base = nameOrPrompt.replace(/^\s*new project\s*:\s*/i, "").toLowerCase().replace(/[^a-z0-9]+/g, " ").trim().split(" ").filter(w => !["a", "an", "the", "that"].includes(w)).slice(0, 5).join("-") || "project";
    let slug = base, i = 2;
    while (existsSync(join(this.o.projectsRoot, slug)) || this.state.projects.has("p_" + slug)) slug = `${base}-${i++}`;
    initProject(join(this.o.projectsRoot, slug));
    const project_id = "p_" + slug, name = slug.replace(/-/g, " ");
    this.emit({ project_id, kind: "project_created", title: clean(`New project: ${name}`, "", 120), name, ...(req_id ? { req_id } : {}) });
    return project_id;
  }

  enqueue(project_id: string, prompt: string, agent: AgentKind, req_id?: string): string {
    const task_id = `t${this.state.taskCount + 1}`;
    const replay = agent === "replay" ? (prompt.match(/\[replay:([\w.-]+)\]/)?.[1] ?? undefined) : undefined;
    const first = this.state.projects.get(project_id)?.tasks[0];
    let phase: TaskMeta["phase"] = first ? undefined : "plan";
    if (first) try { if (this.meta(first.task_id).phase) phase = "followup"; } catch { /* a task from before #972 */ }
    this.saveMeta({ task_id, project_id, prompt, agent, asks: 0, ...(replay ? { replay } : {}), ...(phase ? { phase } : {}) });
    const view = { task_id, project_id, state: undefined as any } as TaskView;
    this.setState(view, "queued", clean(prompt.replace(/^\s*new project\s*:\s*/i, "").replace(/\[replay:[^\]]*\]/, ""), "", 80) || "Task", req_id ? { req_id } : {});
    return task_id;
  }

  /** A task that cannot start because no coding agent is ready: queued, then failed with the next action. */
  private notReady(project_id: string, prompt: string, why: "!missing" | "!signed_out", req_id?: string): string {
    const task_id = this.enqueue(project_id, prompt, "codex", req_id);
    this.finish(this.state.tasks.get(task_id)!, false, why === "!signed_out" ? "agent_signed_out" : "agent_unavailable", {});
    return task_id;
  }

  /** Start what can start: one active task per project (running or waiting for an answer), a cap across projects. */
  pump() {
    const perProject = this.o.perProject ?? 1, maxRunning = this.o.maxRunning ?? 2;
    for (const p of this.state.projects.values()) {
      for (const t of this.state.queued(p.project_id)) {
        if (this.state.active(p.project_id).length >= perProject || this.runs.size >= maxRunning) break;
        this.startTask(t);
      }
    }
  }

  private startTask(t: TaskView) {
    const m = this.meta(t.task_id), pdir = this.projectDir(t.project_id);
    let wt: string;
    try { wt = ensureWorktree(pdir, t.task_id); }
    catch (e) { this.say(String(e)); this.setState(t, "running", "Starting"); return this.finish(t, false, "worktree_failed", {}); }
    const resuming = !!m.session && (!!m.answer || !!m.restarted);
    this.setState(t, "running", m.answer ? "Resumed with your answer" : m.restarted ? "Picking up after a restart" : "Working");
    const tail = `\nWhen you finish, your final message must be only this JSON: {"status":"done","summary":"<one sentence>","question":"","options":[]}. ` +
      `If you need a decision only the person can make (or the task says to ask them first), stop before applying that decision and make your final message only ` +
      `{"status":"question","summary":"","question":"<the question>","options":["<2 to 4 short choices>"]}. ` +
      `When you can, ask it as a yes/no question with the options ["Yes","No"]: the person answers from a headset with one word or one pinch. You will be resumed with the answer.`;
    const task = m.prompt.replace(/^\s*new project\s*:\s*/i, "");
    const prompt = m.answer ? `${m.answer}\nContinue the task. For a prepared first task, apply this answer to the isolated setting, run only its focused check, and return done immediately. Do not rebuild, redesign, add features, or repeat checks already passed.${m.phase ? ` ${WEB_SHAPE}` : ""}${tail}`
      : resuming ? `The host restarted while you were working. The folder holds your work so far. Continue the task.${tail}`
      : m.phase === "plan" ? `You are in a git worktree for the first task of a brand-new project. Work only inside this folder. ` +
        `Do not commit; the host commits and merges your work.\nTask: ${task}\n${WEB_SHAPE}\n${PLAN_ASK}${tail}`
      : m.phase === "followup" ? `You are in a git worktree of an existing project: a static web app whose entry is index.html. Change it as asked, ` +
        `keeping its rules. ${WEB_SHAPE} Do not ask a question; make the change. Do not commit; the host commits and merges your work.\nChange: ${task}${tail}`
      : `You are in a git worktree for one task of a project. Work only inside this folder and keep it small. ` +
        `Run what you build once to check that it works. Do not commit; the host commits and merges your work.\nTask: ${task}${tail}`;
    let replayFile = this.o.replayFile;
    if (m.agent === "replay") {
      if (m.replay) replayFile = join(this.o.replayFile ? join(this.o.replayFile, "..") : ".", m.replay);
      const r = replayFile?.replace(/\.jsonl$/, ".resume.jsonl");
      if (resuming && r && existsSync(r)) replayFile = r;
    }
    const run_id = "r_" + randomBytes(4).toString("hex");
    const raw = join(this.o.dir, "raw", `${run_id}.${m.agent}.jsonl`);
    const base = { project_id: t.project_id, task_id: t.task_id, run_id };
    let signedOut = false;
    const run = startRun({ kind: m.agent, prompt, cwd: wt, replayFile, raw: l => { appendFileSync(raw, l + "\n"); if (!signedOut && (l.startsWith("[stderr]") || /"(is_error":true|type":"(error|turn\.failed)")/.test(l)) && SIGNED_OUT_TEXT.test(l)) signedOut = true; }, schemaFile: join(this.o.dir, "final_schema.json"),
      resume: resuming ? m.session : undefined,
      onSession: id => { const x = this.meta(t.task_id); if (x.session !== id) { x.session = id; this.saveMeta(x); } },
      emit: e => { lastEv = Date.now(); this.emit({ ...base, ...e, ...(e.kind === "started" && resuming ? { title: "Agent resumed" } : {}) } as NewEvent); },
      ask: (decision_id, title, options, allow_text) => {
        lastEv = Date.now();
        this.emit({ ...base, kind: "question", title, decision_id, options, allow_text });
        this.setState(this.state.tasks.get(t.task_id)!, "needs_answer", "Waiting for your answer");
      } });
    this.runs.set(t.task_id, run);
    delete m.answer; m.restarted = false; m.pid = run.child.pid; this.saveMeta(m);
    this.say(`task ${t.task_id} run ${run_id} (${m.agent}) in ${wt}${resuming ? " (resumed)" : ""}`);
    let timedOut = false, lastEv = Date.now(), nudgedAt = 0, stall: "stalled_done" | "stalled" | undefined;
    const cap = setTimeout(() => { timedOut = true; run.cancel(); }, this.o.taskTimeoutMs ?? 30 * 60_000);
    const quietMs = this.o.watchdogQuietMs ?? 120_000, graceMs = this.o.watchdogGraceMs ?? 60_000;
    const dog = setInterval(() => {
      const now = Date.now();
      if (stall || run.canceled) return;
      if (this.state.tasks.get(t.task_id)?.state !== "running") { lastEv = now; return; } // a question waits on the person, not the agent
      const p = run.progress();
      if (!nudgedAt) {
        if (now - lastEv < quietMs || !(p.wrote || p.checked)) return;
        nudgedAt = now;
        const sent = run.nudge(NUDGE);
        this.say(`host watchdog nudge task=${t.task_id} quiet_s=${Math.round((now - lastEv) / 1000)}${sent ? "" : " sent=no"}`);
      } else if (now - Math.max(lastEv, nudgedAt) >= graceMs) {
        stall = p.checked ? "stalled_done" : "stalled";
        this.say(`host watchdog end task=${t.task_id} quiet_s=${Math.round((now - lastEv) / 1000)} outcome=${stall}`);
        run.cancel();
      }
    }, this.o.watchdogTickMs ?? 5_000);
    run.done.then(end => {
      clearTimeout(cap); clearInterval(dog); this.runs.delete(t.task_id);
      if (run.suspended) return;                         // host is stopping: the task stays as it is in the log
      const now = this.state.tasks.get(t.task_id)!;
      const mm = this.meta(t.task_id); mm.session = end.session ?? run.session ?? mm.session; delete mm.pid;
      if (stall) { this.saveMeta(mm); this.finish(now, stall === "stalled_done", stall, base, end.exit_code, end.final?.summary, end.output); }
      else if (timedOut) { this.saveMeta(mm); this.finish(now, false, "timeout", base); }
      else if (run.canceled) { this.saveMeta(mm); this.finish(now, false, "canceled", base); }
      else if (end.ok && end.final?.status === "question" && end.final.question && mm.session) {
        mm.asks++;
        const decision_id = `q_${t.task_id}_${mm.asks}`;
        const parts = mm.phase === "plan" && mm.asks === 1 ? planParts(end.final.parts, wt) : [];
        const labels = (end.final.options.length ? end.final.options : ["Go ahead"]).slice(0, parts.length ? 3 : 6);
        const options = labels.map((l, i) => ({ id: `o${i + 1}`, label: clean(l, wt, 60) }));
        mm.question = { decision_id, text: end.final.question, options, live: false }; this.saveMeta(mm);
        if (parts.length) this.emit({ ...base, kind: "plan", title: clean(`Plan: ${parts.map(p => p.name).join(", ")}`, wt, 160), parts });
        this.emit({ ...base, kind: "question", title: clean(end.final.question, wt, 160), decision_id, options, allow_text: true });
        this.setState(now, "needs_answer", "Waiting for your answer");
      } else { this.saveMeta(mm); this.finish(now, end.ok, end.ok ? undefined : signedOut ? "agent_signed_out" : "agent_failed", base, end.exit_code, end.final?.summary, end.output); }
      this.pump();
    });
  }

  /** The end of a task: on success commit, merge into main and drop the worktree; otherwise keep the worktree and say where. */
  private finish(t: TaskView, ok: boolean, reason: string | undefined, base: { run_id?: string }, exit_code: number | null = null, summary?: string, output?: string) {
    const pdir = this.projectDir(t.project_id), wt = join(pdir, worktreeRel(t.task_id)), branch = branchOf(t.task_id);
    const kept = { branch, worktree: worktreeRel(t.task_id) };
    if (ok) {
      commitIfDirty(wt, `${(summary || t.title).replace(/\s+/g, " ").slice(0, 72)} (${t.task_id})`);
      const r = mergeTask(pdir, t.task_id);
      if (r.merged || r.reason === "no_changes") {
        removeWorktree(pdir, t.task_id);
        this.emit({ project_id: t.project_id, task_id: t.task_id, run_id: base.run_id, kind: "finished", title: r.merged ? "Finished and saved" : "Finished, nothing changed",
          ok: true, exit_code, merged: r.merged, branch, ...(reason ? { reason } : {}), ...(r.commit ? { commit: r.commit } : {}), files: r.files.slice(0, 50),
          ...(output ? { output: clean(output, wt, 160) } : {}) });
        if (r.merged && r.commit) this.preview(t, base.run_id, pdir, r.commit);
        return this.setState(t, "done", "Done", r.commit ? { commit: r.commit } : {});
      }
      reason = r.reason;
    }
    const st: TaskState = reason === "canceled" ? "cancelled" : "failed";
    const title = reason === "canceled" ? "Stopped" : reason === "timeout" ? "Stopped: took too long" : reason === "stalled" ? "Stopped: the agent went quiet" : reason === "merge_conflict" ? "Could not merge"
      // #1474: every failure names the next action.
      : reason === "agent_unavailable" ? "Not started: no coding agent is ready on the computer. Install one and sign in there, then send it again."
      : reason === "agent_signed_out" ? "Stopped: your coding agent is not signed in. Sign in on the computer, then send it again."
      : reason === "worktree_failed" ? "Failed to start: the project folder is not a usable git folder. Check the host window on the computer."
      : "Failed: the agent stopped with an error. Check the host window on the computer, then send it again.";
    if (reason !== "canceled") this.say(`task ${t.task_id} ${reason ?? "failed"}: ${title}${reason === "agent_signed_out" || reason === "agent_unavailable" ? "" : ` Details: ${join(this.o.dir, "raw")}`}`);
    this.emit({ project_id: t.project_id, task_id: t.task_id, run_id: base.run_id, kind: "finished", title, ok: false, exit_code, reason, merged: false,
      ...(existsSync(wt) ? kept : {}) });
    this.setState(t, st, title, existsSync(wt) ? { worktree: kept.worktree, reason } : { reason });
  }

  /** #972: main moved; if it has an index.html the headset can run, say which commit and what its bytes hash to. */
  private preview(t: TaskView, run_id: string | undefined, pdir: string, commit: string) {
    const f = fileAtCommit(pdir, commit, "index.html", MAX_FILE_BYTES, SAFE_PATH);
    if (!f.ok) { if (f.code === "too_large") this.say(`no preview for ${t.task_id}: index.html is over ${MAX_FILE_BYTES} bytes`); return; }
    this.emit({ project_id: t.project_id, task_id: t.task_id, run_id, kind: "preview", title: "Preview ready", commit: f.commit, path: "index.html",
      bytes: f.bytes.length, sha256: sha256(f.bytes) });
  }

  private cancelTask(t: TaskView) {
    const run = this.runs.get(t.task_id);
    if (run) return run.cancel();                         // done handler reports it
    if (t.state === "queued") return this.setState(t, "cancelled", "Removed from the queue", { reason: "canceled" });
    this.finish(t, false, "canceled", {});                // waiting for an answer with no process
    this.pump();
  }

  /** After a crash or a stop: tasks the log says were running have no agent any more. Kill orphans, put them back in line. */
  private recover() {
    for (const t of [...this.state.tasks.values()]) {
      if (t.state !== "running" && t.state !== "needs_answer") continue;
      let m: TaskMeta; try { m = this.meta(t.task_id); } catch { this.setState(t, "failed", "Lost after a restart", { reason: "host_restarted" }); continue; }
      if (m.pid && isAgentPid(m.pid)) { this.say(`killing orphaned agent pid ${m.pid} of ${t.task_id}`); killPidTree(m.pid); }
      delete m.pid;
      if (t.state === "needs_answer" && m.question && !m.question.live) { this.saveMeta(m); continue; } // the question survives; the answer resumes it
      m.restarted = true; delete m.question; this.saveMeta(m);
      this.setState(t, "queued", "Back in line after a restart", { reason: "host_restarted" });
    }
    this.pump();
  }
}

/** The agent's parts, made safe for the headset: unique short ids, names of 24 characters or fewer, relative file paths. */
export function planParts(raw: RawPart[], cwd: string): Part[] {
  const out: Part[] = [], ids = new Set<string>();
  for (const [i, p] of raw.slice(0, 6).entries()) {
    let id = p.id.toLowerCase().replace(/[^a-z0-9_-]+/g, "-").replace(/^-+|-+$/g, "").slice(0, 16);
    if (!id || ids.has(id)) id = `p${i + 1}`;
    if (ids.has(id)) continue;
    const name = clean(p.name, cwd, PART_NAME_MAX) || `Part ${i + 1}`;
    const files = [...new Set(p.files.map(f => clean(f.replace(/\\/g, "/").replace(/^\.\//, ""), cwd, 200)).filter(f => SAFE_PATH.test(f)))].slice(0, 6);
    ids.add(id); out.push({ id, name, files });
  }
  return out;
}
