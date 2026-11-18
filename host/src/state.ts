// The host's state machine, written down first. State is never stored on its own: it is a pure fold over the
// event log (events.jsonl), so a host that was killed rebuilds exactly what the headset last saw.
//
//   project:  created ──(first task starts)──► active
//
//   task:     queued ──► running ──► done | failed | cancelled
//               ▲  │        │  ▲
//               │  │        ▼  │ (answered)
//               │  │    needs_answer ──► cancelled | failed
//               │  └──► cancelled
//               └──── running | needs_answer   (host restarted: the agent process is gone, the task goes back in line)
import type { HostEvent, Part } from "./protocol.ts";

export const TASK_STATES = ["queued", "running", "needs_answer", "done", "failed", "cancelled"] as const;
export type TaskState = typeof TASK_STATES[number];
export const TERMINAL: readonly TaskState[] = ["done", "failed", "cancelled"];

const NEXT: Record<TaskState, readonly TaskState[]> = {
  queued: ["running", "cancelled"],
  running: ["needs_answer", "done", "failed", "cancelled", "queued"],
  needs_answer: ["running", "cancelled", "failed", "queued"],
  done: [], failed: [], cancelled: [],
};
export const canMove = (from: TaskState | undefined, to: TaskState) => from === undefined ? to === "queued" : NEXT[from].includes(to);

export interface TaskView { task_id: string; project_id: string; state: TaskState; title: string; since: string; reason?: string; worktree?: string; commit?: string }
export interface ProjectView { project_id: string; name: string; state: "created" | "active"; tasks: TaskView[]; parts?: Part[] }
export interface Decision { decision_id: string; project_id: string; task_id?: string; run_id?: string; options: { id: string; label: string }[]; title: string; allow_text?: boolean }

export class HostState {
  projects = new Map<string, ProjectView>();
  tasks = new Map<string, TaskView>();
  decisions = new Map<string, Decision>(); // open ones only
  taskCount = 0;
  /** #815: create_project req_id -> what it made, from the log, so a resent request is answered, not run twice. */
  created = new Map<string, { project_id: string; task_id?: string }>();
  /** Fold one event. Throws on an illegal transition: the host checks with canMove before it logs. */
  apply(e: HostEvent) {
    if (e.req_id && (e.kind === "project_created" || (e.kind === "task_state" && e.state === "queued")))
      this.created.set(e.req_id, { project_id: e.project_id, task_id: e.task_id ?? this.created.get(e.req_id)?.task_id });
    if (e.kind === "project_created") {
      this.projects.set(e.project_id, { project_id: e.project_id, name: e.name ?? e.project_id, state: "created", tasks: [] });
    } else if (e.kind === "task_state" && e.task_id && e.state) {
      const to = e.state as TaskState, t = this.tasks.get(e.task_id);
      if (!canMove(t?.state, to)) throw new Error(`illegal transition ${e.task_id}: ${t?.state ?? "(new)"} -> ${to} at seq ${e.seq}`);
      if (!t) {
        const v: TaskView = { task_id: e.task_id, project_id: e.project_id, state: to, title: e.title, since: e.at };
        this.tasks.set(e.task_id, v); this.taskCount++;
        this.projects.get(e.project_id)?.tasks.push(v);
      } else { t.state = to; t.since = e.at; t.reason = e.reason; if (e.worktree !== undefined) t.worktree = e.worktree; if (e.commit) t.commit = e.commit; }
      if (to === "running") { const p = this.projects.get(e.project_id); if (p) p.state = "active"; }
      // A question is open only while its task waits for it.
      if (to !== "needs_answer") for (const [id, d] of this.decisions) if (d.task_id === e.task_id) this.decisions.delete(id);
    } else if (e.kind === "question" && e.decision_id) {
      this.decisions.set(e.decision_id, { decision_id: e.decision_id, project_id: e.project_id, task_id: e.task_id, run_id: e.run_id,
        options: e.options ?? [], title: e.title, allow_text: e.allow_text });
    } else if (e.kind === "plan" && e.parts) {
      const p = this.projects.get(e.project_id); if (p) p.parts = e.parts;   // #972: file_written events are tagged from this
    } else if (e.kind === "answered" && e.decision_id) {
      this.decisions.delete(e.decision_id);
    } else if (e.kind === "finished" && !e.task_id) {
      // skeleton-era runs had no task: their questions close with the run
      for (const [id, d] of this.decisions) if (d.run_id === e.run_id) this.decisions.delete(id);
    }
  }
  static from(events: HostEvent[]) { const s = new HostState(); for (const e of events) s.apply(e); return s; }
  active(project_id: string) { return [...this.tasks.values()].filter(t => t.project_id === project_id && (t.state === "running" || t.state === "needs_answer")); }
  queued(project_id: string) { return [...this.tasks.values()].filter(t => t.project_id === project_id && t.state === "queued"); }
  summary() {
    return [...this.projects.values()].map(p => ({ project_id: p.project_id, name: p.name, state: p.state,
      tasks: p.tasks.map(t => ({ task_id: t.task_id, state: t.state, title: t.title })) }));
  }
}
