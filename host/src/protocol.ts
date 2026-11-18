// Wire contract v1 (docs/host/PROTOCOL.md). One validator for both directions, used by host, client and fixture tests.
import { createHash } from "node:crypto";

export const PROTOCOL_VERSION = 1;
export const DEFAULT_PORT = 47821;
export const TOKEN_TTL_MS = 120_000;
export const CODE_TTL_MS = 120_000;
export const MAX_CODE_TRIES = 5;
export const MAX_FAILS_PER_MIN = 10;
export const AUTH_TIMEOUT_MS = 10_000;
/** #972 preview files: at most this many bytes per file, and this many base64 chars per `file_chunk` (4500 raw bytes). */
export const MAX_FILE_BYTES = 512 * 1024;
export const CHUNK_B64 = 6000;
export const CHUNK_BYTES = CHUNK_B64 / 4 * 3;
export const PART_NAME_MAX = 24;
/** #1035: the longest sentence a `say` frame carries to an agent. */
export const SAY_MAX = 400;
/** A tracked file path a client may ask for: relative, `/`-separated, plain characters, no `.` or `..` segment. */
export const SAFE_PATH = /^(?!.*(?:^|\/)\.\.?(?:\/|$))[A-Za-z0-9._-]+(?:\/[A-Za-z0-9._-]+)*$/;
export interface Part { id: string; name: string; files: string[] }

export const EVENT_KINDS = ["started", "message", "file_written", "command_run", "question", "answered", "finished", "error", "project_created", "task_state", "plan", "preview"] as const;
export const STATES = ["queued", "running", "needs_answer", "done", "failed", "cancelled"] as const;
export type EventKind = typeof EVENT_KINDS[number];
export const ERROR_CODES = ["bad_request", "unauthorized", "bad_token", "token_expired", "token_used", "bad_code", "code_expired",
  "rate_limited", "unknown_project", "unknown_decision", "busy", "agent_unavailable", "agent_signed_out", "internal", "bad_path", "not_found", "too_large"] as const;
export type ErrorCode = typeof ERROR_CODES[number];

export interface HostEvent {
  type: "event"; seq: number; at: string; project_id: string; kind: EventKind; title: string;
  run_id?: string; detail?: string; path?: string; command?: string; exit_code?: number | null;
  decision_id?: string; options?: { id: string; label: string }[]; option_id?: string;
  ok?: boolean; reason?: string; commit?: string; files?: string[];
  task_id?: string; state?: string; name?: string; branch?: string; worktree?: string; merged?: boolean; allow_text?: boolean; text?: string;
  /** #815: the create_project request this project/queued task came from (lets a client that lost the ok see it in the log). */
  req_id?: string;
  /** #815: on `finished`, the first line the program printed the last time the agent ran it successfully. */
  output?: string;
  /** #972: `plan` carries the agent's parts; `file_written` carries the part its path belongs to. */
  parts?: Part[]; part_id?: string;
  /** #972: `preview` (and `file_chunk`): size and SHA-256 (lowercase hex) of the file at `commit`. */
  bytes?: number; sha256?: string;
}

const CHECK_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
/** 4-character check both screens show: binds the pinned certificate to the secret the wearer used. */
export function checkCode(fp: string, secret: string): string {
  const d = createHash("sha256").update(`flowstate-check|${fp}|${secret}`).digest();
  return [0, 1, 2, 3].map(i => CHECK_ALPHABET[d[i] & 31]).join("");
}

export function pairUri(o: { h: string; p: number; fp: string; t: string; n: string }): string {
  const q = new URLSearchParams({ v: String(PROTOCOL_VERSION), h: o.h, p: String(o.p), fp: o.fp, t: o.t, n: o.n });
  return `flowstate://pair?${q}`;
}
export function parsePairUri(s: string): { h: string; p: number; fp: string; t: string; n: string } {
  const u = new URL(s.trim());
  if (u.protocol !== "flowstate:" || u.hostname !== "pair") throw new Error("not a pairing code");
  const g = (k: string) => { const v = u.searchParams.get(k); if (!v) throw new Error(`pairing code missing ${k}`); return v; };
  if (g("v") !== String(PROTOCOL_VERSION)) throw new Error("unsupported pairing version");
  const fp = g("fp"); if (!/^[0-9a-f]{64}$/.test(fp)) throw new Error("bad fingerprint");
  return { h: g("h"), p: Number(g("p")), fp, t: g("t"), n: g("n") };
}

// ---- validation -------------------------------------------------------------------------------------------
type Spec = Record<string, "s" | "s?" | "n" | "n?" | "b?" | "a?" | "nn?" | "k" | "e" | "sa?">;
const C2H: Record<string, Spec> = {
  pair_token: { token: "s", device_name: "s" },
  pair_code: { code: "s", device_name: "s" },
  pair_request: { device_pub: "s", device_name: "s" }, // confirm-on-computer (2d); pairing_confirm.ts
  pair_nonce: { nonce: "s" },
  resume: { device_id: "s", device_secret: "s", last_seq: "n" },
  create_project: { req_id: "s", prompt: "s", name: "s?", agent: "s?" },
  prompt: { req_id: "s", project_id: "s", text: "s" },
  answer: { req_id: "s", decision_id: "s", option_id: "s?", text: "s?" },
  cancel: { req_id: "s", project_id: "s", task_id: "s?" },
  get_file: { req_id: "s", project_id: "s", commit: "s", path: "s", offset: "n" },
  // #1035 "talk to an agent": one spoken sentence for one running task's own agent session, nowhere else.
  say: { req_id: "s", project_id: "s", task_id: "s", text: "s" },
};
const H2C: Record<string, Spec> = {
  paired: { device_id: "s", device_secret: "s", host_name: "s", check: "s" },
  pair_commit: { c: "s" }, pair_reveal: { nonce: "s" }, pair_pending: { digits_hint: "s" }, pair_refused: { reason: "s" },
  welcome: { host_name: "s", head_seq: "n", open_decisions: "sa?", projects: "a?" },
  ok: { req_id: "s", project_id: "s?", task_id: "s?" },
  error: { code: "e", message: "s", req_id: "s?" },
  file_chunk: { req_id: "s", path: "s", sha256: "s", offset: "n", total: "n", data: "s" },
  event: { seq: "n", at: "s", project_id: "s", kind: "k", title: "s", run_id: "s?", detail: "s?", path: "s?", command: "s?",
    exit_code: "nn?", decision_id: "s?", options: "a?", option_id: "s?", ok: "b?", reason: "s?", commit: "s?", files: "sa?",
    task_id: "s?", state: "s?", name: "s?", branch: "s?", worktree: "s?", merged: "b?", allow_text: "b?", text: "s?", req_id: "s?", output: "s?",
    parts: "a?", part_id: "s?", bytes: "n?", sha256: "s?" },
};

function check(o: any, spec: Spec): string | null {
  for (const [k, t] of Object.entries(spec)) {
    const v = o[k], opt = t.endsWith("?");
    if (v === undefined) { if (opt) continue; return `missing ${k}`; }
    const base = t.replace("?", "");
    const ok = base === "s" ? typeof v === "string" && v.length <= 8000
      : base === "n" ? Number.isInteger(v) && v >= 0
      : base === "nn" ? v === null || Number.isInteger(v)
      : base === "b" ? typeof v === "boolean"
      : base === "a" ? Array.isArray(v)
      : base === "sa" ? Array.isArray(v) && v.every(x => typeof x === "string")
      : base === "k" ? (EVENT_KINDS as readonly string[]).includes(v)
      : base === "e" ? (ERROR_CODES as readonly string[]).includes(v) : false;
    if (!ok) return `bad ${k}`;
  }
  for (const k of Object.keys(o)) if (k !== "type" && !(k in spec)) return `unknown field ${k}`;
  return null;
}

const HEX64 = /^[0-9a-f]{64}$/;
function checkParts(f: any): string | null {
  if (!f.task_id || !Array.isArray(f.parts) || f.parts.length < 1 || f.parts.length > 6) return "plan needs task_id and 1 to 6 parts";
  const ids = new Set<string>();
  for (const p of f.parts) {
    if (!p || typeof p.id !== "string" || !/^[a-z0-9_-]{1,16}$/.test(p.id) || ids.has(p.id)) return "plan part needs a unique id";
    if (typeof p.name !== "string" || !p.name || p.name.length > PART_NAME_MAX) return "plan part name must be 1 to 24 chars";
    if (!Array.isArray(p.files) || p.files.length > 6 || !p.files.every((x: any) => typeof x === "string" && SAFE_PATH.test(x))) return "plan part files must be safe relative paths";
    if (Object.keys(p).some(k => !["id", "name", "files"].includes(k))) return "unknown plan part field";
    ids.add(p.id);
  }
  return null;
}

/** Returns null when the frame is valid for that direction, else a reason. */
export function validate(frame: unknown, dir: "c2h" | "h2c"): string | null {
  if (!frame || typeof frame !== "object" || Array.isArray(frame)) return "frame is not an object";
  const f = frame as any, table = dir === "c2h" ? C2H : H2C;
  if (typeof f.type !== "string" || !(f.type in table)) return `unknown type ${f.type}`;
  const r = check(f, table[f.type]); if (r) return r;
  if (f.type === "pair_code" && !/^\d{6}$/.test(f.code)) return "code must be 6 digits";
  if (f.type === "event" && f.kind === "question" && (!f.decision_id || !Array.isArray(f.options) || !f.options.length)) return "question needs decision_id and options";
  if (f.type === "event" && f.kind === "finished" && typeof f.ok !== "boolean") return "finished needs ok";
  if (f.type === "event" && f.kind === "question" && !f.options.every((o: any) => o && typeof o.id === "string" && typeof o.label === "string")) return "question options need id and label";
  if (f.type === "event" && f.kind === "task_state" && (!f.task_id || !(STATES as readonly string[]).includes(f.state))) return "task_state needs task_id and a known state";
  if (f.type === "event" && f.kind === "project_created" && !f.name) return "project_created needs name";
  if (f.type === "event" && f.kind === "plan") { const r = checkParts(f); if (r) return r; }
  if (f.type === "event" && f.kind === "preview" && (!f.commit || !/^[0-9a-f]{40}$/.test(f.commit) || !f.path || f.bytes === undefined || !HEX64.test(f.sha256 ?? "")))
    return "preview needs commit (40 hex), path, bytes and sha256";
  if (f.type === "get_file" && (!/^[0-9a-f]{7,40}$/.test(f.commit) || f.path.length > 200)) return "get_file needs a hex commit and a short path";
  if (f.type === "file_chunk" && (!HEX64.test(f.sha256) || f.data.length > CHUNK_B64 || !/^[A-Za-z0-9+/]*={0,2}$/.test(f.data) || f.total > MAX_FILE_BYTES || f.offset > f.total))
    return "bad file_chunk";
  if (f.type === "answer" && f.option_id === undefined && !f.text) return "answer needs option_id or text";
  if (f.type === "say" && (!f.text.trim() || f.text.length > SAY_MAX)) return `say needs 1 to ${SAY_MAX} characters`;
  if (f.type === "paired" && !/^[A-Z2-9]{4}$/.test(f.check)) return "bad check";
  if ((f.type === "pair_nonce" || f.type === "pair_reveal") && !/^[A-Za-z0-9_-]{22}$/.test(f.nonce)) return "nonce must be 16 bytes base64url";
  if (f.type === "pair_commit" && !/^[0-9a-f]{64}$/.test(f.c)) return "bad commitment";
  if (f.type === "pair_pending" && f.digits_hint !== "none") return "the host never sends the digits";
  if (f.type === "pair_refused" && !["declined", "expired", "busy", "rate_limited", "out_of_order"].includes(f.reason)) return "bad reason";
  return null;
}
