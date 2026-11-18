// Schema loading, validation and DAG stats shared by every mode.
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, resolve, isAbsolute } from "node:path";
import { existsSync } from "node:fs";
import AjvMod from "ajv";

export type Status = "SUBMITTED" | "WORKING" | "INPUT_REQUIRED" | "COMPLETED" | "FAILED" | "CANCELED";
export type Kind =
  | "task_assigned" | "plan" | "step_start" | "tool_call" | "tool_result" | "step_end"
  | "status" | "message" | "artifact" | "done" | "error" | "decision" | "metric" | "budget";

export interface AgentEvent {
  schema_version?: "1.0" | "1.1";
  project?: string;
  project_name?: string;
  decision_id?: string;
  recommendation?: string;
  deadline_ms?: number;
  blocking?: boolean;
  evidence?: string;
  name?: string;
  value?: number;
  pct?: number;
  pace_pct?: number;
  t_ms: number;
  run_id?: string;
  agent_id: string;
  agent_name?: string;
  kind: Kind;
  step_id?: string | null;
  parent_step_id?: string | null;
  depends_on?: string[];
  retry_of?: string;
  attempt?: number;
  title?: string;
  detail?: string;
  status?: Status;
  tool?: string;
  priority?: "low" | "normal" | "high";
  options?: { id: string; label: string; gesture?: string }[];
  default_option?: string;
  answer?: string;
  plan?: string[];
}

export type ClientMessage =
  | { kind: "task_assigned"; agent_id: string; task: string; priority?: "low" | "normal" | "high"; task_id?: string }
  | { kind: "input_response"; agent_id: string; step_id?: string; decision_id?: string; option_id: string; text?: string };

const here = dirname(fileURLToPath(import.meta.url));
export const AGENTS_DIR = resolve(here, "..", "..");
export const SCHEMA_PATH = resolve(AGENTS_DIR, "schema", "events.schema.json");
export const FIXTURES_DIR = resolve(AGENTS_DIR, "fixtures");

const Ajv: any = (AjvMod as any).default ?? AjvMod;
const schema = JSON.parse(readFileSync(SCHEMA_PATH, "utf8"));
const ajv = new Ajv({ allErrors: true, strict: false });
ajv.addSchema(schema, "events");
const validateEventFn = ajv.getSchema("events#/definitions/event")!;
const validateClientFn = ajv.getSchema("events#/definitions/client_message")!;

function fmt(errs: any[] | null | undefined): string {
  return (errs ?? []).map((e) => `${e.instancePath || "/"} ${e.message}`).join("; ");
}

export function validateEvent(e: unknown): string | null {
  return validateEventFn(e) ? null : fmt(validateEventFn.errors);
}
export function validateClientMessage(m: unknown): string | null {
  return validateClientFn(m) ? null : fmt(validateClientFn.errors);
}

/** Resolve a fixture path given relative to cwd, agents/, or agents/fixtures/. */
export function resolveFixture(p: string): string {
  const cands = isAbsolute(p) ? [p] : [resolve(process.cwd(), p), resolve(AGENTS_DIR, p), resolve(FIXTURES_DIR, p)];
  for (const c of cands) if (existsSync(c)) return c;
  throw new Error(`fixture not found: ${p} (tried ${cands.join(", ")})`);
}

export function readJsonl(path: string): AgentEvent[] {
  return readFileSync(path, "utf8").split(/\r?\n/).filter((l) => l.trim()).map((l, i) => {
    try { return JSON.parse(l); } catch (err) { throw new Error(`${path}:${i + 1}: bad JSON: ${err}`); }
  });
}

/** Full-fixture check: per-event schema + stream invariants. Returns list of problems. */
export function checkStream(events: AgentEvent[]): string[] {
  const probs: string[] = [];
  const started = new Set<string>();
  const ended = new Set<string>();
  let last = -1;
  events.forEach((e, i) => {
    const err = validateEvent(e);
    if (err) probs.push(`#${i}: ${err}`);
    if (e.t_ms < last) probs.push(`#${i}: t_ms ${e.t_ms} < previous ${last}`);
    last = e.t_ms;
    if (e.kind === "step_start") {
      if (started.has(e.step_id!)) probs.push(`#${i}: duplicate step_start ${e.step_id}`);
      if (e.parent_step_id && !started.has(e.parent_step_id)) probs.push(`#${i}: parent ${e.parent_step_id} not started`);
      for (const d of e.depends_on ?? []) if (!started.has(d)) probs.push(`#${i}: depends_on ${d} not started`);
      started.add(e.step_id!);
    } else if (e.step_id && !started.has(e.step_id) && e.kind !== "status") {
      probs.push(`#${i}: ${e.kind} references unknown step ${e.step_id}`);
    }
    if (e.kind === "step_end") ended.add(e.step_id!);
  });
  for (const s of started) if (!ended.has(s)) probs.push(`step ${s} never ended`);
  return probs;
}

export interface DagStats {
  events: number; agents: number; steps: number; depth: number; width: number;
  fanout_max: number; fanin_max: number; duration_s: number; failures: number; retries: number; input_required: number;
}

/** Depth = longest root->leaf path (nodes) over parent + depends_on edges; width = max nodes at one depth level. */
export function dagStats(events: AgentEvent[]): DagStats {
  const preds = new Map<string, string[]>();
  const children = new Map<string, number>();
  for (const e of events) {
    if (e.kind !== "step_start") continue;
    const ps = [...(e.parent_step_id ? [e.parent_step_id] : []), ...(e.depends_on ?? [])];
    preds.set(e.step_id!, ps);
    for (const p of ps) children.set(p, (children.get(p) ?? 0) + 1);
  }
  const level = new Map<string, number>();
  const lv = (s: string): number => {
    if (level.has(s)) return level.get(s)!;
    const ps = preds.get(s) ?? [];
    const v = ps.length ? 1 + Math.max(...ps.map(lv)) : 1;
    level.set(s, v);
    return v;
  };
  const counts = new Map<number, number>();
  for (const s of preds.keys()) counts.set(lv(s), (counts.get(lv(s)) ?? 0) + 1);
  return {
    events: events.length,
    agents: new Set(events.map((e) => e.agent_id).filter((a) => a !== "user")).size,
    steps: preds.size,
    depth: Math.max(0, ...level.values()),
    width: Math.max(0, ...counts.values()),
    fanout_max: Math.max(0, ...children.values()),
    fanin_max: Math.max(0, ...[...preds.values()].map((p) => p.length)),
    duration_s: Math.round((events.at(-1)?.t_ms ?? 0) / 1000),
    failures: events.filter((e) => e.kind === "step_end" && e.status === "FAILED").length,
    retries: events.filter((e) => e.kind === "step_start" && e.retry_of).length,
    input_required: events.filter((e) => e.kind === "status" && e.status === "INPUT_REQUIRED").length,
  };
}
