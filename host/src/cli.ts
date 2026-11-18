// Command line for a running host. `new`, `answer` and `stop <task>` go over the same pinned TLS link and the same
// frames as the headset (the CLI is the paired device "d_local"); `status` folds the event log on disk, so it works
// whether the host is up or not; `stop` with no task stops the host (its tasks resume on the next start).
import { existsSync, readFileSync, rmSync } from "node:fs";
import { join } from "node:path";
import { spawnSync } from "node:child_process";
import { HeadsetClient } from "./client.ts";
import { EventLog } from "./eventlog.ts";
import { HostState } from "./state.ts";
import { configDir } from "./config.ts";
import { LOCAL_DEVICE } from "./server.ts";

interface HostInfo { pid: number; address: string; port: number; fp: string }
const alive = (pid: number) => { try { process.kill(pid, 0); return true; } catch { return false; } };

export function hostInfo(dir: string): HostInfo | undefined {
  const f = join(dir, "host.json");
  if (!existsSync(f)) return undefined;
  const h: HostInfo = JSON.parse(readFileSync(f, "utf8"));
  return alive(h.pid) ? h : undefined;
}

export async function connectLocal(dir: string): Promise<{ c: HeadsetClient; welcome: any }> {
  const h = hostInfo(dir);
  if (!h) throw new Error("The host is not running. Start it with: flowstate-host");
  const c = await new HeadsetClient(h.address, h.port, h.fp).open();
  const head = new EventLog(join(dir, "events.jsonl")).head;
  c.lastSeq = head;
  c.send({ type: "resume", device_id: LOCAL_DEVICE, device_secret: readFileSync(join(dir, "cli.secret"), "utf8").trim(), last_seq: head });
  const welcome = await c.waitFor(f => f.type === "welcome" || f.type === "error", 10000);
  if (welcome.type !== "welcome") throw new Error(welcome.message);
  return { c, welcome };
}

async function request(dir: string, frame: any, follow?: (c: HeadsetClient, ok: any) => Promise<void>) {
  const { c } = await connectLocal(dir);
  c.send(frame);
  const r = await c.waitFor(f => (f.type === "ok" || f.type === "error") && f.req_id === frame.req_id, 15000);
  if (r.type === "error") { c.close(); throw new Error(r.message); }
  if (follow) await follow(c, r);
  c.close();
  return r;
}

export function statusText(dir: string): string {
  const h = hostInfo(dir);
  const s = HostState.from(new EventLog(join(dir, "events.jsonl")).events);
  const out = [h ? `host: running (pid ${h.pid}) on ${h.address}:${h.port}` : "host: stopped"];
  for (const p of s.projects.values()) {
    out.push(`${p.project_id}  (${p.state})`);
    for (const t of p.tasks) out.push(`  ${t.task_id.padEnd(5)} ${t.state.padEnd(13)} ${t.title}${t.commit ? `  [${t.commit}]` : ""}${t.worktree ? `  kept in ${t.worktree}` : ""}${t.reason ? `  (${t.reason})` : ""}`);
  }
  if (s.decisions.size) {
    out.push("open questions:");
    for (const d of s.decisions.values()) out.push(`  ${d.decision_id} [${d.task_id ?? "-"}] ${d.title}  ->  ${d.options.map(o => `${o.id}=${o.label}`).join(", ")}`);
  }
  return out.join("\n");
}

export async function cli(argv: string[]): Promise<number> {
  const opt = (k: string) => { const i = argv.indexOf(`--${k}`); return i >= 0 ? argv[i + 1] : undefined; };
  const dir = opt("config") ?? configDir();
  const pos = argv.filter((a, i) => !a.startsWith("--") && !(i > 0 && argv[i - 1].startsWith("--") && !["--follow"].includes(argv[i - 1])));
  const [cmd, a1, a2] = pos;
  const req_id = `cli${Date.now()}`;
  if (cmd === "status") { console.log(statusText(dir)); return 0; }
  if (cmd === "new") {
    const prompt = opt("prompt") ?? a1;
    if (!prompt) { console.error('usage: flowstate-host new "<name>" --prompt "<what to build>"'); return 2; }
    const frame: any = { type: "create_project", req_id, prompt, ...(a1 && opt("prompt") ? { name: a1 } : {}), ...(opt("agent") ? { agent: opt("agent") } : {}) };
    const r = await request(dir, frame, argv.includes("--follow") ? followTask : undefined);
    console.log(`project ${r.project_id}, task ${r.task_id} queued`);
    return 0;
  }
  if (cmd === "answer") {
    if (!a1 || !a2) { console.error("usage: flowstate-host answer <question id> <option id | label | free text>"); return 2; }
    const d = HostState.from(new EventLog(join(dir, "events.jsonl")).events).decisions.get(a1);
    const o = d?.options.find(x => x.id === a2 || x.label.toLowerCase() === a2.toLowerCase());
    await request(dir, { type: "answer", req_id, decision_id: a1, ...(o ? { option_id: o.id } : { text: a2 }) });
    console.log("answered"); return 0;
  }
  if (cmd === "stop") {
    if (a1) {
      const t = HostState.from(new EventLog(join(dir, "events.jsonl")).events).tasks.get(a1);
      if (!t) { console.error(`no task ${a1}`); return 2; }
      await request(dir, { type: "cancel", req_id, project_id: t.project_id, task_id: a1 });
      console.log(`stopping ${a1}`); return 0;
    }
    const h = hostInfo(dir);
    if (!h) { console.log("host: not running"); return 0; }
    if (process.platform === "win32") spawnSync("taskkill", ["/PID", String(h.pid), "/T", "/F"], { stdio: "ignore" });
    else process.kill(h.pid, "SIGTERM");
    for (let i = 0; i < 50 && alive(h.pid); i++) await new Promise(r => setTimeout(r, 100));
    try { rmSync(join(dir, "host.json")); } catch { /* removed by the host */ }
    console.log(alive(h.pid) ? `host pid ${h.pid} did not stop` : "host stopped; running tasks go back in line on the next start");
    return alive(h.pid) ? 1 : 0;
  }
  console.error("usage: flowstate-host [start] [options] | new \"<name>\" --prompt \"<text>\" [--follow] | status | answer <id> <choice> | stop [<task>]");
  return 2;
}

async function followTask(c: HeadsetClient, ok: any) {
  for (;;) {
    const e = await c.waitFor(f => f.type === "event" && f.task_id === ok.task_id, 3_600_000);
    console.log(`#${e.seq} ${e.kind}${e.state ? ` ${e.state}` : ""}: ${e.title}`);
    if (e.kind === "question") console.log(`   answer with: flowstate-host answer ${e.decision_id} <${e.options.map((o: any) => o.label).join(" | ")}>`);
    if (e.kind === "task_state" && ["done", "failed", "cancelled", "needs_answer"].includes(e.state)) return;
  }
}
