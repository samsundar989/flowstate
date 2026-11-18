// Minimal headset stand-in: prints events, answers INPUT_REQUIRED with the first option, optionally assigns a task.
//   npm run client -- [--url ws://127.0.0.1:8765] [--assign builder="Write the changelog"] [--manual]
import WebSocket from "ws";
import { argOf } from "./server.ts";

const args = process.argv.slice(2);
const ws = new WebSocket(argOf(args, "url", "ws://127.0.0.1:8765")!);
let n = 0;
ws.on("message", (b) => {
  const e = JSON.parse(b.toString());
  n++;
  console.log(`${String(e.t_ms).padStart(7)} ${e.agent_id.padEnd(10)} ${e.kind.padEnd(12)} ${e.status ?? ""} ${e.title ?? ""}`);
  if (e.kind === "status" && e.status === "INPUT_REQUIRED" && !args.includes("--manual")) {
    ws.send(JSON.stringify({ kind: "input_response", agent_id: e.agent_id, step_id: e.step_id, option_id: e.options[0].id }));
  }
});
ws.on("open", () => {
  const a = argOf(args, "assign");
  if (a) { const k = a.indexOf("="); ws.send(JSON.stringify({ kind: "task_assigned", agent_id: a.slice(0, k), task: a.slice(k + 1).replace(/^"|"$/g, "") })); }
});
ws.on("close", () => { console.error(`closed after ${n} events`); process.exit(0); });
process.on("SIGINT", () => { console.error(`received ${n} events`); process.exit(0); });
