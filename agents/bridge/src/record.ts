// Record real Claude Code runs (concurrently) into a schema fixture.
//   npm run record -- --cwd <toy repo> --out ../fixtures/real_claude_run.jsonl [--model haiku] \
//        --agent "researcher=Read the code and ..." --agent "builder=Add ..." [--raw raw.tsv] [--redact <literal> ...] [-- <extra claude args>]
import { writeFileSync, appendFileSync } from "node:fs";
import { resolve } from "node:path";
import { argOf } from "./server.ts";
import { Converter, runClaude } from "./claude.ts";
import { checkStream, dagStats, type AgentEvent } from "./schema.ts";

const all = process.argv.slice(2);
const dd = all.indexOf("--");
const args = dd >= 0 ? all.slice(0, dd) : all;
const extra = dd >= 0 ? all.slice(dd + 1) : [];
const cwd = resolve(argOf(args, "cwd")!);
const out = resolve(argOf(args, "out", "real_claude_run.jsonl")!);
const raw = argOf(args, "raw");
const model = argOf(args, "model");
const redact = args.flatMap((a, i) => (a === "--redact" ? [args[i + 1]] : []));
const agents = args.flatMap((a, i) => (a === "--agent" ? [args[i + 1]] : [])).map((s) => { const k = s.indexOf("="); return { id: s.slice(0, k), task: s.slice(k + 1) }; });
if (!agents.length) { console.error("need at least one --agent id=task"); process.exit(2); }
if (raw) writeFileSync(raw, "");

const t0 = Date.now();
const run_id = `real-${new Date(t0).toISOString().slice(0, 16).replace(/[:T]/g, "")}`;
const events: AgentEvent[] = [];
const runs = agents.map((a) => {
  const c = new Converter(a.id, { t0, cwd, run_id, redact });
  return runClaude(a.task, c, (e) => { events.push(e); process.stdout.write(`${e.t_ms} ${e.agent_id} ${e.kind} ${e.title ?? ""}\n`); },
    { cwd, model, extraArgs: extra, rawLog: raw ? (l) => appendFileSync(raw, `${a.id}\t${l}\n`) : undefined });
});
const codes = await Promise.all(runs.map((r) => r.done));
// Stable sort by time, then clamp so the file is monotonic even if stream timestamps and local clock disagree.
const sorted = events.map((e, i) => [e, i] as const).sort((a, b) => a[0].t_ms - b[0].t_ms || a[1] - b[1]).map(([e]) => e);
let last = 0;
for (const e of sorted) { e.t_ms = Math.max(last, e.t_ms); last = e.t_ms; }
writeFileSync(out, sorted.map((e) => JSON.stringify(e)).join("\n") + "\n");
console.error(`exit codes ${codes.join(",")}; wrote ${out}`);
console.error(JSON.stringify({ problems: checkStream(sorted).slice(0, 10), ...dagStats(sorted) }));
