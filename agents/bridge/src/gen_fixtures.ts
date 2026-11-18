// Deterministic synthesiser for the 3-agent replay fixtures.
//   npm run gen-fixtures        -> writes agents/fixtures/{feature_ratelimit,bugfix_flaky_test,research_vector_db}.jsonl
// Each scenario: 3 concurrent agents, branching DAG with fan-out/fan-in, one failure+retry, one INPUT_REQUIRED.
import { writeFileSync } from "node:fs";
import { resolve } from "node:path";
import { FIXTURES_DIR, type AgentEvent } from "./schema.ts";

interface Tool { tool: string; input: string; result: string; s: number; fail?: boolean }
interface Ask { prompt: string; options: { id: string; label: string; gesture: string }[]; answer: string; wait_s: number }
interface Step {
  id: string; agent: string; title: string;
  parent?: string;        // defaults to the agent's root step
  deps?: string[];        // fan-in edges (may cross agents)
  par?: boolean;          // part of a parallel fan-out group (runs alongside siblings)
  think?: number;         // seconds before first tool call
  plan?: string[];
  tools: Tool[];
  msg?: string; artifact?: string; ask?: Ask; retryOf?: string;
  cancel?: boolean;       // step_end CANCELED + agent status CANCELED, then the agent resumes WORKING
}
interface Scenario { name: string; seed: number; label?: string; agents: { id: string; name: string; task: string; start_s: number }[]; steps: Step[] }

const NAMES: Record<string, string> = { researcher: "Researcher", builder: "Builder", reviewer: "Reviewer" };

function rng(seed: number) {
  return () => { seed |= 0; seed = (seed + 0x6d2b79f5) | 0; let t = Math.imul(seed ^ (seed >>> 15), 1 | seed); t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t; return ((t ^ (t >>> 14)) >>> 0) / 4294967296; };
}

export function synthesise(sc: Scenario): AgentEvent[] {
  const r = rng(sc.seed);
  const j = (s: number) => Math.round(s * 1000 * (0.85 + 0.3 * r())); // jittered ms
  const out: AgentEvent[] = [];
  const end = new Map<string, number>();
  const cursor = new Map<string, number>();
  const groupStart = new Map<string, number | null>();
  const rootOf = (a: string) => `${a}.root`;
  const failed = new Map<string, number>();
  const emit = (e: AgentEvent) => out.push(e);

  for (const [ai, a] of sc.agents.entries()) {
    const t0 = a.start_s * 1000;
    const label = ai === 0 && sc.label ? { detail: `Scripted scenario: ${sc.label}` } : {};
    emit({ t_ms: t0, agent_id: a.id, agent_name: a.name, kind: "task_assigned", title: a.task, priority: "normal", status: "SUBMITTED", ...label });
    emit({ t_ms: t0 + 400, agent_id: a.id, kind: "status", status: "SUBMITTED", title: "Queued" });
    emit({ t_ms: t0 + 1200, agent_id: a.id, kind: "step_start", step_id: rootOf(a.id), parent_step_id: null, title: a.task });
    emit({ t_ms: t0 + 1300, agent_id: a.id, kind: "status", status: "WORKING", step_id: rootOf(a.id), title: "Working" });
    cursor.set(a.id, t0 + 1300);
  }

  for (const st of sc.steps) {
    const a = st.agent;
    const depsEnd = Math.max(0, ...(st.deps ?? []).map((d) => {
      if (!end.has(d)) throw new Error(`${sc.name}: ${st.id} depends on unscheduled ${d}`);
      return end.get(d)!;
    }));
    let start: number;
    if (st.par) {
      if (groupStart.get(a) == null) groupStart.set(a, cursor.get(a)!);
      start = Math.max(groupStart.get(a)!, depsEnd) + j(0.8);
    } else {
      groupStart.set(a, null);
      start = Math.max(cursor.get(a)!, depsEnd) + j(1.5);
    }
    let t = start;
    const attempt = st.retryOf ? (failed.get(st.retryOf) ?? 1) + 1 : undefined;
    emit({
      t_ms: t, agent_id: a, kind: "step_start", step_id: st.id, parent_step_id: st.parent ?? rootOf(a), title: st.title,
      ...(st.deps?.length ? { depends_on: st.deps } : {}),
      ...(st.retryOf ? { retry_of: st.retryOf, attempt } : {}),
    });
    if (st.plan) { t += j(2); emit({ t_ms: t, agent_id: a, kind: "plan", step_id: st.id, title: "Plan", plan: st.plan }); }
    t += j(st.think ?? 3);
    let stepFailed = false;
    for (const tl of st.tools) {
      emit({ t_ms: t, agent_id: a, kind: "tool_call", step_id: st.id, tool: tl.tool, title: `${tl.tool}`, detail: tl.input });
      t += j(tl.s);
      emit({ t_ms: t, agent_id: a, kind: "tool_result", step_id: st.id, tool: tl.tool, status: tl.fail ? "FAILED" : "COMPLETED", title: tl.fail ? `${tl.tool} failed` : `${tl.tool} ok`, detail: tl.result });
      if (tl.fail) stepFailed = true;
      t += j(1.2);
    }
    if (st.ask) {
      emit({ t_ms: t, agent_id: a, kind: "status", status: "INPUT_REQUIRED", step_id: st.id, title: st.ask.prompt, options: st.ask.options, default_option: st.ask.answer });
      t += j(st.ask.wait_s);
      const opt = st.ask.options.find((o) => o.id === st.ask!.answer)!;
      emit({ t_ms: t, agent_id: a, kind: "status", status: "WORKING", step_id: st.id, title: `User chose: ${opt.label}`, answer: opt.id });
      t += j(2);
    }
    if (st.msg) { t += j(2); emit({ t_ms: t, agent_id: a, kind: "message", step_id: st.id, title: st.msg.split(/[.:]/)[0].slice(0, 60), detail: st.msg }); }
    if (st.artifact) { t += j(1); emit({ t_ms: t, agent_id: a, kind: "artifact", step_id: st.id, title: st.artifact }); }
    t += j(0.5);
    emit({ t_ms: t, agent_id: a, kind: "step_end", step_id: st.id, status: st.cancel ? "CANCELED" : stepFailed ? "FAILED" : "COMPLETED", title: st.title });
    if (st.cancel) {
      t += 300;
      emit({ t_ms: t, agent_id: a, kind: "status", status: "CANCELED", step_id: st.id, title: `Stopped: ${st.title}`.slice(0, 60) });
      t += j(1.5);
      emit({ t_ms: t, agent_id: a, kind: "status", status: "WORKING", step_id: rootOf(a), title: "Restarting" });
    }
    if (stepFailed) {
      failed.set(st.id, attempt ?? 1);
      t += 300;
      emit({ t_ms: t, agent_id: a, kind: "error", step_id: st.id, title: `${st.title} failed; retrying`, detail: st.tools.find((x) => x.fail)!.result });
    }
    end.set(st.id, t);
    cursor.set(a, Math.max(cursor.get(a)!, t));
  }

  for (const a of sc.agents) {
    const t = cursor.get(a.id)! + j(2);
    emit({ t_ms: t, agent_id: a.id, kind: "step_end", step_id: rootOf(a.id), status: "COMPLETED", title: a.task });
    emit({ t_ms: t + 200, agent_id: a.id, kind: "done", step_id: rootOf(a.id), status: "COMPLETED", title: "Done" });
    emit({ t_ms: t + 300, agent_id: a.id, kind: "status", status: "COMPLETED", title: "Completed" });
  }
  // Stable sort keeps causal order for equal timestamps.
  return out.map((e, i) => [e, i] as const).sort((x, y) => x[0].t_ms - y[0].t_ms || x[1] - y[1]).map(([e]) => ({ run_id: sc.name, ...e }));
}

const APPROVE = (label: string) => ({ id: "approve", label, gesture: "thumbs_up" });
const REDIRECT = (label: string) => ({ id: "redirect", label, gesture: "swipe_left" });

const S = (id: string, agent: string, title: string, tools: Tool[], extra: Partial<Step> = {}): Step => ({ id, agent, title, tools, ...extra });
const T = (tool: string, input: string, result: string, s: number, fail = false): Tool => ({ tool, input, result, s, ...(fail ? { fail } : {}) });

export const SCENARIOS: Scenario[] = [
  {
    name: "feature_ratelimit", seed: 11,
    agents: [
      { id: "researcher", name: "Researcher", task: "Map the API surface that needs rate limiting", start_s: 0 },
      { id: "builder", name: "Builder", task: "Add per-key rate limiting middleware", start_s: 2 },
      { id: "reviewer", name: "Reviewer", task: "Review the rate limiting change", start_s: 4 },
    ],
    steps: [
      S("r1", "researcher", "List API routes", [T("Glob", "src/api/**/*.ts", "23 files", 2), T("Grep", "router\\.(get|post|put|delete)", "61 matches in 14 files", 4)], { plan: ["List routes", "Read auth, billing, search handlers", "Check existing limits", "Write findings"] }),
      S("r2a", "researcher", "Read auth handlers", [T("Read", "src/api/auth/login.ts", "142 lines", 3), T("Read", "src/api/auth/token.ts", "88 lines", 3)], { parent: "r1", par: true, think: 6 }),
      S("r2b", "researcher", "Read billing handlers", [T("Read", "src/api/billing/charge.ts", "201 lines", 4)], { parent: "r1", par: true, think: 8 }),
      S("r2c", "researcher", "Read search handlers", [T("Read", "src/api/search/query.ts", "176 lines", 4), T("Grep", "storeClient", "src/lib/cache.ts:12: import storeClient", 3)], { parent: "r1", par: true, think: 7 }),
      S("r3", "researcher", "Check for existing limits", [T("Grep", "rateLimit|throttle", "0 matches", 3), T("WebSearch", "token bucket rate-limit store per api key", "5 results (express-rate-limit, rate-limiter-flexible, ...)", 9)], { deps: ["r2a", "r2b", "r2c"], think: 12 }),
      S("r4", "researcher", "Write findings", [T("Write", "notes/ratelimit_findings.md", "wrote 46 lines", 3)], { parent: "r3", think: 15, msg: "Findings: 61 routes, no limiter today. Rate-limit store client already in src/lib/cache.ts. Recommend token bucket keyed on API key, stricter on /auth.", artifact: "notes/ratelimit_findings.md" }),
      S("b1", "builder", "Plan the middleware", [T("Read", "src/app.ts", "97 lines", 2), T("Read", "src/lib/cache.ts", "54 lines", 2)], { plan: ["Wait for findings", "Write limiter", "Wire into app", "Tests", "Commit"], think: 8 }),
      S("b2", "builder", "Write token bucket limiter", [T("Write", "src/middleware/rateLimit.ts", "wrote 74 lines", 4), T("Edit", "src/lib/cache.ts", "added incrWithTtl()", 3)], { deps: ["r4"], think: 25 }),
      S("b3", "builder", "Wire limiter into routes", [T("Edit", "src/app.ts", "app.use('/api', rateLimit({ perMinute: 120 }))", 3), T("Edit", "src/api/auth/router.ts", "stricter: 10/min on /login", 3)], { parent: "b2", think: 10 }),
      S("b4", "builder", "Write tests", [T("Write", "test/rateLimit.test.ts", "wrote 9 tests", 6)], { parent: "b2", think: 20 }),
      S("b5", "builder", "Run tests", [T("Bash", "npm test -- rateLimit", "8 passed, 1 failed: 'resets after window' expected 200 got 429", 35, true)], { deps: ["b3", "b4"], think: 3 }),
      S("b6", "builder", "Fix window reset", [T("Read", "src/middleware/rateLimit.ts", "74 lines", 2), T("Edit", "src/middleware/rateLimit.ts", "TTL set in ms, not s: fixed", 4)], { parent: "b5", think: 18 }),
      S("b5r", "builder", "Run tests (retry)", [T("Bash", "npm test -- rateLimit", "9 passed", 33)], { retryOf: "b5", deps: ["b6"], think: 2 }),
      S("b7", "builder", "Commit", [T("Bash", "git add -A && git commit -m 'Add per-key rate limiting'", "[feat/ratelimit 3c1e9a2] 5 files changed", 3)], { parent: "b5r", artifact: "commit 3c1e9a2" }),
      S("v1", "reviewer", "Read findings", [T("Read", "notes/ratelimit_findings.md", "46 lines", 3)], { deps: ["r4"], think: 5 }),
      S("v2", "reviewer", "Review diff", [T("Bash", "git diff main...feat/ratelimit --stat", "5 files, +212 -3", 3), T("Read", "src/middleware/rateLimit.ts", "79 lines", 5)], { deps: ["b7"], think: 20, msg: "Diff looks right. One concern: limiter fails closed if the rate-limit store is down." }),
      S("v3", "reviewer", "Ask: fail open or closed?", [], { parent: "v2", think: 2, ask: { prompt: "Rate-limit store down: fail open (allow) or fail closed (429)?", options: [APPROVE("Fail open"), REDIRECT("Fail closed")], answer: "approve", wait_s: 20 } }),
      S("v4", "reviewer", "Run full suite", [T("Bash", "npm test", "312 passed", 48)], { parent: "v3", think: 3 }),
      S("v5", "reviewer", "Write review", [T("Write", "notes/review_ratelimit.md", "wrote 18 lines", 3)], { deps: ["v4"], think: 10, msg: "Approved with fail-open fallback.", artifact: "notes/review_ratelimit.md" }),
    ],
  },
  {
    name: "bugfix_flaky_test", seed: 23,
    agents: [
      { id: "researcher", name: "Researcher", task: "Find why checkout.e2e is flaky", start_s: 0 },
      { id: "builder", name: "Builder", task: "Fix the flaky checkout test", start_s: 3 },
      { id: "reviewer", name: "Reviewer", task: "Confirm the flake is gone", start_s: 5 },
    ],
    steps: [
      S("r1", "researcher", "Pull recent CI failures", [T("Bash", "repo-cli run list --workflow ci --status failure -L 20", "7 failed runs, 6 in checkout.e2e", 6)], { plan: ["Pull CI failures", "Read 3 logs in parallel", "Correlate", "Report"] }),
      S("r2a", "researcher", "Read CI log #4812", [T("Bash", "repo-cli run view 4812 --log-failed", "TimeoutError: waiting for #pay-confirm (5000ms)", 8)], { parent: "r1", par: true }),
      S("r2b", "researcher", "Read CI log #4797", [T("Bash", "repo-cli run view 4797 --log-failed", "TimeoutError: waiting for #pay-confirm (5000ms)", 9)], { parent: "r1", par: true }),
      S("r2c", "researcher", "Read CI log #4790", [T("Bash", "repo-cli run view 4790 --log-failed", "expect(total).toBe('$42.00') got '$0.00'", 7)], { parent: "r1", par: true }),
      S("r3", "researcher", "Correlate failures", [T("Grep", "pay-confirm", "e2e/checkout.spec.ts:58, src/ui/Pay.tsx:112", 3), T("Read", "src/ui/Pay.tsx", "Pay button enabled before cart total resolves", 4)], { deps: ["r2a", "r2b", "r2c"], think: 15, msg: "Root cause: Pay button renders before cart total fetch resolves; test clicks too early under load." }),
      S("b1", "builder", "Reproduce locally", [T("Bash", "npx playwright test checkout --repeat-each 20", "3/20 failed", 55)], { think: 5 }),
      S("b2", "builder", "Choose fix", [], { deps: ["r3"], think: 10, ask: { prompt: "Fix the race in the app or just raise the test timeout?", options: [APPROVE("Fix race in app"), REDIRECT("Raise timeout")], answer: "approve", wait_s: 25 } }),
      S("b3", "builder", "Disable Pay until total loads", [T("Edit", "src/ui/Pay.tsx", "disabled={!total.ready}", 4), T("Edit", "e2e/checkout.spec.ts", "await expect(pay).toBeEnabled()", 3)], { parent: "b2", think: 15 }),
      S("b4", "builder", "Run e2e x20", [T("Bash", "npx playwright test checkout --repeat-each 20", "1/20 failed: '$0.00' total", 52, true)], { parent: "b3" }),
      S("b5", "builder", "Fix total fallback", [T("Read", "src/store/cart.ts", "total defaults to 0 before fetch", 3), T("Edit", "src/store/cart.ts", "total: null until fetched", 4)], { parent: "b4", think: 20 }),
      S("b4r", "builder", "Run e2e x20 (retry)", [T("Bash", "npx playwright test checkout --repeat-each 20", "20/20 passed", 50)], { retryOf: "b4", deps: ["b5"] }),
      S("b6", "builder", "Commit fix", [T("Bash", "git commit -am 'Fix checkout race'", "[fix/checkout 9ad0b11]", 3)], { parent: "b4r", artifact: "commit 9ad0b11" }),
      S("v1", "reviewer", "Review diff", [T("Bash", "git show 9ad0b11 --stat", "3 files, +14 -6", 3), T("Read", "src/store/cart.ts", "65 lines", 4)], { deps: ["b6"], think: 12 }),
      S("v2a", "reviewer", "Stress run: browser-a", [T("Bash", "npx playwright test checkout --project browser-a --repeat-each 30", "30/30 passed", 60)], { parent: "v1", par: true }),
      S("v2b", "reviewer", "Stress run: browser-b", [T("Bash", "npx playwright test checkout --project browser-b --repeat-each 30", "30/30 passed", 70)], { parent: "v1", par: true }),
      S("v2c", "reviewer", "Stress run: browser-c", [T("Bash", "npx playwright test checkout --project browser-c --repeat-each 30", "30/30 passed", 66)], { parent: "v1", par: true }),
      S("v3", "reviewer", "Sign off", [T("Write", "notes/flake_review.md", "wrote 12 lines", 3)], { deps: ["v2a", "v2b", "v2c"], think: 8, msg: "90/90 green across three browsers. Approved.", artifact: "notes/flake_review.md" }),
    ],
  },
  {
    name: "research_vector_db", seed: 37,
    agents: [
      { id: "researcher", name: "Researcher", task: "Compare vector databases for 10M embeddings", start_s: 0 },
      { id: "builder", name: "Builder", task: "Benchmark the top two candidates", start_s: 2 },
      { id: "reviewer", name: "Reviewer", task: "Fact-check and assemble the report", start_s: 6 },
    ],
    steps: [
      S("r1", "researcher", "Define criteria", [T("Read", "docs/requirements.md", "p95 < 50ms, 10M x 768d, self-hosted", 3)], { plan: ["Criteria", "Search 4 candidates", "Shortlist", "Hand off"], think: 6 }),
      S("r2a", "researcher", "Search: relational vector extension", [T("WebSearch", "relational vector extension hnsw 10 million vectors benchmark", "8 results", 10), T("WebFetch", "repo host: relational vector extension README", "HNSW + IVFFlat, halfvec", 8)], { parent: "r1", par: true }),
      S("r2b", "researcher", "Search: the self-hosted vector store", [T("WebSearch", "self-hosted-store 10M vectors memory footprint", "7 results", 11), T("WebFetch", "self-hosted vector store benchmarks", "p95 9-30ms at 10M", 9)], { parent: "r1", par: true }),
      S("r2c", "researcher", "Search: the clustered vector store", [T("WebSearch", "clustered vector store standalone vs cluster 10M", "9 results", 10)], { parent: "r1", par: true }),
      S("r2d", "researcher", "Search: disk vector store", [T("WebSearch", "disk vector store disk based ann benchmark", "6 results", 9), T("WebFetch", "disk vector store documentation", "IVF-PQ on disk", 7)], { parent: "r1", par: true }),
      S("r3", "researcher", "Shortlist", [T("Write", "notes/vector_db_shortlist.md", "wrote 38 lines", 4)], { deps: ["r2a", "r2b", "r2c", "r2d"], think: 18, msg: "Shortlist: relational vector extension (ops simplicity) and the self-hosted vector store (latency). The clustered vector store is too heavy for one box.", artifact: "notes/vector_db_shortlist.md" }),
      S("b1", "builder", "Prepare dataset", [T("Bash", "python scripts/make_embeddings.py --n 1000000 --dim 768", "wrote data/emb_1m.npy (2.9 GB)", 40)], { think: 6, plan: ["Dataset", "Benchmark harness", "Run both", "Summarise"] }),
      S("b2", "builder", "Write benchmark harness", [T("Write", "bench/run.py", "wrote 120 lines", 6)], { deps: ["r3"], think: 20 }),
      S("b3", "builder", "Run harness", [T("Bash", "python bench/run.py --db self-hosted-store,relational-vector-extension", "ModuleNotFoundError: No module named 'vector_client'", 4, true)], { parent: "b2" }),
      S("b4", "builder", "Install client", [T("Bash", "pip install vector-client psycopg[binary]", "Successfully installed vector-client-1.12.1", 15)], { parent: "b3", think: 4 }),
      S("b3r", "builder", "Run harness (retry)", [T("Bash", "python bench/run.py --db self-hosted-store,relational-vector-extension", "self-hosted-store p95 14ms, relational-vector-extension p95 41ms, recall@10 0.97/0.95", 75)], { retryOf: "b3", deps: ["b4", "b1"] }),
      S("b5a", "builder", "Plot latency", [T("Bash", "python bench/plot.py --metric latency", "wrote out/latency.png", 6)], { parent: "b3r", par: true, artifact: "out/latency.png" }),
      S("b5b", "builder", "Plot recall", [T("Bash", "python bench/plot.py --metric recall", "wrote out/recall.png", 6)], { parent: "b3r", par: true, artifact: "out/recall.png" }),
      S("v1", "reviewer", "Fact-check shortlist", [T("WebFetch", "self-hosted vector store capacity guide", "~1.2x raw vector size in RAM", 9), T("WebFetch", "repo host: relational vector extension README, HNSW section", "build time grows with m, ef_construction", 8)], { deps: ["r3"], think: 10, msg: "Shortlist claims check out; the clustered vector store note is opinion, flag it." }),
      S("v2", "reviewer", "Ask: include pricing?", [], { parent: "v1", think: 2, ask: { prompt: "Include a managed-hosting pricing table in the report?", options: [APPROVE("Yes, include"), REDIRECT("No, self-hosted only")], answer: "redirect", wait_s: 18 } }),
      S("v3", "reviewer", "Assemble report", [T("Read", "notes/vector_db_shortlist.md", "38 lines", 2), T("Write", "reports/vector_db.md", "wrote 92 lines with 2 figures", 8)], { deps: ["v2", "b5a", "b5b"], think: 25, msg: "Recommendation: the self-hosted vector store for latency-critical search, the relational vector extension if ops simplicity wins.", artifact: "reports/vector_db.md" }),
    ],
  },
  // ---- Shot fixtures (task #52): short, single-purpose runs for video takes and onboarding. ----
  {
    // S1/S7/S8: Reviewer asks a yes/no at ~20 s, then two tool calls and COMPLETED; the others work on.
    name: "shot_reviewer_needs_input", seed: 52, label: "Reviewer needs input (video S1, S7, S8)",
    agents: [
      { id: "reviewer", name: "Reviewer", task: "Review and apply the orders migration", start_s: 0 },
      { id: "researcher", name: "Researcher", task: "Survey slow queries on the orders table", start_s: 1 },
      { id: "builder", name: "Builder", task: "Add an index-backed orders search", start_s: 2 },
    ],
    steps: [
      S("v1", "reviewer", "Check the migration", [T("Read", "migrations/0042_orders_status.py", "adds orders.status NOT NULL DEFAULT 'open'", 3), T("Bash", "alembic upgrade head --sql | wc -l", "38 lines of SQL, no drops", 4)], { think: 8, ask: { prompt: "Apply the database migration to staging?", options: [{ id: "approve", label: "Yes, apply it", gesture: "thumbs_up" }, { id: "reject", label: "No, hold off", gesture: "thumbs_down" }], answer: "approve", wait_s: 6 } }),
      S("v2", "reviewer", "Apply migration to staging", [T("Bash", "alembic -x env=staging upgrade head", "Running upgrade 0041 -> 0042, ok", 9), T("Bash", "pytest tests/db -q", "27 passed", 12)], { parent: "v1", think: 2, msg: "Migration 0042 applied to staging; 27 DB tests pass." }),
      S("r1", "researcher", "Pull slow query log", [T("Bash", "psql -c 'select query, mean_ms from pg_stat_statements order by 2 desc limit 10'", "top: orders by customer_id, 840 ms", 6)], { plan: ["Slow query log", "Explain the top two", "Recommend indexes"], think: 4 }),
      S("r2a", "researcher", "Explain query 1", [T("Bash", "psql -c 'explain analyze select * from orders where customer_id = 7'", "Seq Scan on orders, 812 ms", 8)], { parent: "r1", par: true }),
      S("r2b", "researcher", "Explain query 2", [T("Bash", "psql -c 'explain analyze select * from orders where status = open order by created_at'", "Sort + Seq Scan, 610 ms", 9)], { parent: "r1", par: true }),
      S("r3", "researcher", "Recommend indexes", [T("Write", "notes/orders_indexes.md", "wrote 22 lines", 4)], { deps: ["r2a", "r2b"], think: 12, msg: "Two indexes: orders(customer_id) and orders(status, created_at)." }),
      S("b1", "builder", "Read orders search handler", [T("Read", "src/api/orders/search.ts", "118 lines", 3), T("Grep", "orders.find", "4 matches in 2 files", 3)], { think: 5 }),
      S("b2", "builder", "Write search query", [T("Edit", "src/api/orders/search.ts", "filter by status, paginate by created_at", 6)], { parent: "b1", think: 12 }),
      S("b3", "builder", "Add index migration", [T("Write", "migrations/0043_orders_idx.py", "wrote 19 lines", 4)], { deps: ["r3"], think: 6 }),
      S("b4", "builder", "Run search tests", [T("Bash", "npm test -- orders/search", "14 passed", 22)], { deps: ["b2", "b3"], think: 3 }),
    ],
  },
  {
    // S4/S5/S9: Builder takes "export the report as CSV", grows a 9-step graph, ends on one file artifact.
    name: "shot_builder_csv_export", seed: 53, label: "Builder CSV export (video S4, S5, S9)",
    agents: [
      { id: "builder", name: "Builder", task: "Export the report as CSV", start_s: 0 },
      { id: "researcher", name: "Researcher", task: "Check how users download reports today", start_s: 2 },
      { id: "reviewer", name: "Reviewer", task: "Review the open report PRs", start_s: 4 },
    ],
    steps: [
      S("b1", "builder", "Plan the CSV export", [T("Read", "src/report/export.ts", "exports PDF and JSON only", 3)], { plan: ["Read report model", "Write CSV serializer", "Add export route", "Test", "Export sample"], think: 4 }),
      S("b2", "builder", "Read report model", [T("Read", "src/report/model.ts", "Report { rows: Row[], columns: Column[] }", 3)], { parent: "b1", par: true }),
      S("b3", "builder", "Check for a CSV library", [T("Grep", "csv package.json", "no csv dependency", 2)], { parent: "b1", par: true }),
      S("b4", "builder", "Write CSV serializer", [T("Write", "src/report/csv.ts", "wrote 48 lines", 5)], { deps: ["b2", "b3"], think: 8 }),
      S("b5", "builder", "Escape quotes and commas", [T("Edit", "src/report/csv.ts", "quote fields that contain a comma, quote or newline", 4)], { parent: "b4", think: 5 }),
      S("b6", "builder", "Add export route", [T("Edit", "src/api/report.ts", "GET /report/:id/export?format=csv", 4)], { parent: "b4", think: 5 }),
      S("b7", "builder", "Write tests", [T("Write", "test/report_csv.test.ts", "wrote 7 tests", 5)], { deps: ["b5", "b6"], think: 6 }),
      S("b8", "builder", "Run tests", [T("Bash", "npm test -- report_csv", "7 passed", 10)], { parent: "b7", think: 2 }),
      S("b9", "builder", "Export the sample report", [T("Bash", "npm run export -- --report q3 --format csv --out exports/q3_report.csv", "wrote 1,284 rows, 9 columns", 5)], { parent: "b8", think: 3, msg: "Report exported as CSV: 1,284 rows, 9 columns.", artifact: "exports/q3_report.csv" }),
      S("r1", "researcher", "Search support tickets", [T("Grep", "export|download|csv tickets/2026-10/*.md", "31 tickets mention CSV", 5)], { think: 5 }),
      S("r2", "researcher", "Summarise requests", [T("Read", "tickets/2026-10/summary.md", "top ask: open in a spreadsheet", 4)], { parent: "r1", think: 8, msg: "31 October tickets ask for CSV; most want to open it in a spreadsheet." }),
      S("v1", "reviewer", "List open PRs", [T("Bash", "repo-cli pr list --label report", "2 open PRs", 4)], { think: 4 }),
      S("v2", "reviewer", "Review PR #318", [T("Bash", "repo-cli pr diff 318 --stat", "3 files, +41 -9", 4), T("Read", "src/report/pdf.ts", "88 lines", 5)], { parent: "v1", think: 10, msg: "PR #318 looks good; left one nit on page margins." }),
    ],
  },
  {
    // S10: Builder starts on the wrong branch, asks "continue on feature/x?", is told no (thumbs-down),
    // cancels that step and restarts from main, then COMPLETED.
    name: "shot_wrong_branch_redirect", seed: 54, label: "Wrong branch, redirect (video S10)",
    agents: [
      { id: "builder", name: "Builder", task: "Fix the retry backoff bug", start_s: 0 },
      { id: "researcher", name: "Researcher", task: "Find where retries are configured", start_s: 2 },
      { id: "reviewer", name: "Reviewer", task: "Triage new issues", start_s: 4 },
    ],
    steps: [
      S("b1", "builder", "Switch to working branch", [T("Bash", "git switch feature/x", "Switched to branch 'feature/x'", 2)], { plan: ["Pick branch", "Fix backoff", "Test", "Commit"], think: 3 }),
      S("b2", "builder", "Patch backoff on feature/x", [T("Read", "src/net/retry.ts", "delay = base * attempt (linear)", 3), T("Bash", "git status -sb", "## feature/x...origin/feature/x [behind 41]", 2)], { parent: "b1", think: 4, cancel: true, ask: { prompt: "feature/x is 41 commits behind main. Continue on feature/x?", options: [{ id: "continue", label: "Continue on feature/x", gesture: "thumbs_up" }, { id: "stop", label: "No, start from main", gesture: "thumbs_down" }], answer: "stop", wait_s: 7 } }),
      S("b3", "builder", "Restart from main on fix/retry-backoff", [T("Bash", "git switch -c fix/retry-backoff main", "Switched to a new branch 'fix/retry-backoff'", 2)], { think: 2 }),
      S("b4", "builder", "Fix backoff", [T("Read", "src/net/retry.ts", "delay = base * attempt (linear)", 3), T("Edit", "src/net/retry.ts", "delay = base * 2 ** attempt + jitter", 4)], { parent: "b3", think: 6 }),
      S("b5", "builder", "Run tests", [T("Bash", "npm test -- retry", "11 passed", 10)], { parent: "b4", think: 2 }),
      S("b6", "builder", "Commit", [T("Bash", "git commit -am 'Exponential retry backoff'", "[fix/retry-backoff 5e7c210] 2 files changed", 2)], { parent: "b5", artifact: "commit 5e7c210" }),
      S("r1", "researcher", "Grep retry config", [T("Grep", "retry|backoff src/", "src/net/retry.ts, src/config.ts:18", 4)], { think: 4 }),
      S("r2", "researcher", "Read config", [T("Read", "src/config.ts", "retries: 5, base_ms: 200", 3)], { parent: "r1", think: 6, msg: "Retries live in src/net/retry.ts; base 200 ms, 5 attempts." }),
      S("v1", "reviewer", "List new issues", [T("Bash", "repo-cli issue list --label new", "4 issues", 4)], { think: 4 }),
      S("v2", "reviewer", "Label issues", [T("Bash", "repo-cli issue edit 211 212 --add-label bug", "labelled 2 issues", 4)], { parent: "v1", think: 8 }),
    ],
  },
];

if (process.argv[1]?.endsWith("gen_fixtures.ts")) {
  for (const sc of SCENARIOS) {
    const evs = synthesise(sc);
    const p = resolve(FIXTURES_DIR, `${sc.name}.jsonl`);
    writeFileSync(p, evs.map((e) => JSON.stringify(e)).join("\n") + "\n");
    console.log(`${p}: ${evs.length} events, ${(evs.at(-1)!.t_ms / 60000).toFixed(1)} min`);
  }
}
