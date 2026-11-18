// Live mode: real Claude Code agents -> same schema -> WebSocket :8765.
//   npm run live -- --cwd ../../some-repo [--model sonnet] [--port 8765] [--hook-port 8766] [--allow-edits]
// Sources (any mix):
//   1. headset task_assigned {agent_id, task}  -> spawns `claude -p <task> --output-format stream-json --verbose` in --cwd
//   2. Claude Code HTTP hooks: POST http://<pc>:8766/hook?agent=builder  (hook payload JSON body)
//   3. stdin: `claude -p "..." --output-format stream-json --verbose | npm run live -- --stdin --agent builder`
// input_response is logged but cannot be injected into a headless `claude -p` run (see schema/README.md).
import { createServer } from "node:http";
import { createInterface } from "node:readline";
import { resolve } from "node:path";
import { Hub, argOf } from "./server.ts";
import { Converter, runClaude } from "./claude.ts";

const args = process.argv.slice(2);
const port = Number(argOf(args, "port", "8765"));
const hookPort = Number(argOf(args, "hook-port", "8766"));
const cwd = resolve(argOf(args, "cwd", process.cwd())!);
const model = argOf(args, "model");
const extra = args.includes("--allow-edits") ? ["--permission-mode", "acceptEdits"] : [];
const t0 = Date.now();
const hub = new Hub(port);
const convs = new Map<string, Converter>();
const conv = (agent: string) => {
  if (!convs.has(agent) || (convs.get(agent) as any).finished) convs.set(agent, new Converter(agent, { t0, cwd }));
  return convs.get(agent)!;
};
const busy = new Set<string>();

hub.onClient = (m) => {
  if (m.kind === "input_response") { console.error(`input_response for ${m.agent_id}: ${m.option_id} (not injectable into claude -p)`); return; }
  if (busy.has(m.agent_id)) {
    hub.send({ t_ms: Date.now() - t0, agent_id: m.agent_id, kind: "error", title: "Agent busy", detail: `${m.agent_id} is already running; task not started: ${m.task}` });
    return;
  }
  busy.add(m.agent_id);
  const c = new Converter(m.agent_id, { t0, cwd });
  convs.set(m.agent_id, c);
  runClaude(m.task, c, (e) => hub.send(e), { cwd, model, extraArgs: extra }).done.then(() => busy.delete(m.agent_id));
};

createServer((req, res) => {
  const url = new URL(req.url ?? "/", "http://x");
  if (req.method !== "POST" || url.pathname !== "/hook") { res.writeHead(404).end(); return; }
  let body = "";
  req.on("data", (b) => (body += b));
  req.on("end", () => {
    try {
      const h = JSON.parse(body);
      const agent = url.searchParams.get("agent") ?? h.agent_id ?? `session-${String(h.session_id ?? "x").slice(0, 6)}`;
      for (const e of conv(agent).feedHook(h)) hub.send(e);
      res.writeHead(200, { "content-type": "application/json" }).end("{}"); // empty = no decision, Claude proceeds
    } catch (err) { res.writeHead(400).end(String(err)); }
  });
}).listen(hookPort);

if (args.includes("--stdin")) {
  const agent = argOf(args, "agent", "builder")!;
  const rl = createInterface({ input: process.stdin });
  rl.on("line", (l) => { for (const e of conv(agent).feedStreamJson(l)) hub.send(e); });
}

await hub.ready();
console.error(`live: ws://0.0.0.0:${port}  hooks: http://0.0.0.0:${hookPort}/hook?agent=<id>  cwd=${cwd}`);
