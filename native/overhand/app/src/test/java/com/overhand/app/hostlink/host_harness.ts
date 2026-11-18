// Test harness for the hostlink JVM tests: runs the REAL host (host/src/server.ts) on loopback with a test clock,
// and takes JSON commands on stdin so a Kotlin test can drive drops, questions and clock jumps.
// Usage (cwd = <repo>/host): node --experimental-strip-types --no-warnings <this file> <repoRoot> <configDir>
import { createInterface } from "node:readline";
import { join } from "node:path";
import { pathToFileURL } from "node:url";

const [repo, dir] = process.argv.slice(2);
const { Host } = await import(pathToFileURL(join(repo, "host/src/server.ts")).href);
const { startRelay } = await import(pathToFileURL(join(repo, "host/test/mitm_relay.ts")).href);
// HOSTLINK_BIND: the LAN address to listen on (the Wi-Fi adapter's), as the real host does; loopback by default.
const BIND = process.env.HOSTLINK_BIND || "127.0.0.1";
const asks: { name: string; from: string; digits: string }[] = [];
let relay: any;
let clock = Date.now();
const counts: Record<string, number> = {};
const answers: { decision_id: string; option_id: string }[] = [];
let killOnAnswer = false;
// #815: drop the next create_project before the host sees it ("before"), or right after it was handled ("after").
let createDrop: "" | "before" | "after" = "";

function make(port: number) {
  const h = new Host({ bind: BIND, port, dir: join(dir, "cfg"), projectsRoot: join(dir, "projects"), agent: "replay",
    now: () => clock, log: () => {} });
  h.confirm.onAsk = (a: any) => asks.push(a);
  // #815 (after #785): the test's agent run is a stand-in kept in host.runs, so a restart must not try to relaunch it.
  (h as any).recover = () => {};
  const orig = (h as any).handle.bind(h);
  (h as any).handle = (f: any, fail: any, send: any) => {
    counts[f.type] = (counts[f.type] ?? 0) + 1;
    if (f.type === "create_project" && createDrop === "before") { createDrop = ""; kill(); return; }
    const r = orig(f, fail, send);
    if (f.type === "create_project" && createDrop === "after") { createDrop = ""; kill(); }
    return r;
  };
  return h;
}
let connections = 0; // WebSocket upgrades that completed (after TLS): each resume or pairing attempt is one
async function startHost(h: any) { const a = await h.start(); h.wss.on("connection", () => { connections++; }); return a; }
let host = make(0);
let addr = await startHost(host);
const kill = () => { let n = 0; for (const c of (host as any).wss.clients) { c.terminate(); n++; } return n; };
/** Headsets paired over the network (a #785 host also keeps "d_local" for its own command line). */
const paired = () => host.devices.devices.filter((d: any) => d.device_id !== "d_local").length;
const out = (o: object) => process.stdout.write(JSON.stringify(o) + "\n");
const info = () => ({ address: BIND, port: addr.port, fp: host.id.fp, name: host.id.name, token: host.pairing.token.value, code: host.pairing.code.value,
  head: host.log.head, devices: paired() });
out({ ready: true, ...info() });

// #785 hosts keep tasks: one project with one task "t_test" whose agent is this stand-in (answers are recorded).
const TASK = "t_test";
function fakeRun() {
  return { child: {} as any, suspended: false, canceled: false, cancel: () => {}, done: new Promise(() => {}),
    answer: (decision_id: string, option_id: string) => {
      answers.push({ decision_id, option_id });
      if (killOnAnswer) { killOnAnswer = false; kill(); } // the answer lands; the ok and the answered event are never sent on this socket
      return true;
    } };
}
function fakeProject() {
  const pid = "p_test", h = host as any;
  if (!h.state.projects.has(pid)) {
    h.emit({ project_id: pid, kind: "project_created", title: "New project: test project", name: "test project" });
    h.emit({ project_id: pid, task_id: TASK, kind: "task_state", state: "queued", title: "test" });
    h.emit({ project_id: pid, task_id: TASK, kind: "task_state", state: "running", title: "Working" });
  }
  if (!h.runs.has(TASK)) h.runs.set(TASK, fakeRun());
  return pid;
}

for await (const line of createInterface({ input: process.stdin })) {
  if (!line.trim()) continue;
  const c = JSON.parse(line);
  try {
    if (c.cmd === "info") out(info());
    else if (c.cmd === "rotate") { host.pairing.rotate(); out(info()); }
    else if (c.cmd === "advance") { clock += c.ms; out(info()); }
    else if (c.cmd === "emit") {
      const pid = fakeProject();
      for (let i = 0; i < c.n; i++) {
        host.log.append({ project_id: pid, run_id: "r_test", kind: "message", title: `Step ${i + 1}`, detail: `step ${i + 1}` });
        if (c.killAt?.includes(i)) kill();
        if (c.interval) await new Promise(r => setTimeout(r, c.interval));
      }
      out(info());
    }
    else if (c.cmd === "ask") {
      const pid = fakeProject();
      const h = host as any, t = h.state.tasks.get(TASK);
      if (t.state === "needs_answer") h.emit({ project_id: pid, task_id: TASK, kind: "task_state", state: "running", title: "Working" }) ;
      h.emit({ project_id: pid, task_id: TASK, run_id: "r_test", kind: "question", title: c.title ?? "Allow running the tests?", decision_id: c.decision,
        options: [{ id: "allow", label: "Yes" }, { id: "deny", label: "No" }] });
      h.emit({ project_id: pid, task_id: TASK, kind: "task_state", state: "needs_answer", title: "Waiting for your answer" });
      out(info());
    }
    else if (c.cmd === "killOnAnswer") { killOnAnswer = true; out(info()); }
    else if (c.cmd === "createDrop") { createDrop = c.when; out(info()); }
    else if (c.cmd === "projects") out({ projects: [...(host as any).state.projects.keys()], created: [...(host as any).state.created.entries()] });
    else if (c.cmd === "kill") out({ killed: kill() });
    else if (c.cmd === "stats") out({ counts, answers, connections, clients: (host as any).wss.clients.size, ...info() });
    else if (c.cmd === "move") { // the host comes back on another port (DHCP lease change, restart): same identity, same log
      await host.stop(); host = make(0); addr = await startHost(host);
      fakeProject(); // the agent run survived the move
      out(info());
    }
    else if (c.cmd === "confirm") out({ pending: !!host.confirm.pending, digits: host.confirm.pending?.digits ?? null, asks: asks.length, devices: paired() });
    else if (c.cmd === "decide") out({ decided: host.confirm.decide(!!c.yes), devices: paired() });
    else if (c.cmd === "tick") { host.confirm.tick(); out({ pending: !!host.confirm.pending }); }
    else if (c.cmd === "relay") { await relay?.close(); relay = await startRelay({ bind: BIND, host: BIND, hostPort: addr.port, hostFp: host.id.fp, mode: c.mode }); out({ relay_port: relay.port, relay_fp: relay.fp }); } // not "fp": RealHost keeps replies with fp as host info
    else if (c.cmd === "relaySeen") out({ ...relay.seen });
    else if (c.cmd === "oldHost") { // a host from before confirm-on-computer: pair_request is an unknown frame (validator rejects it)
      host.confirm.frame = (cc: any) => { cc.send({ type: "error", code: "bad_request", message: "That request was not understood." }); cc.close(4400, "unknown type pair_request"); };
      out(info());
    }
    else if (c.cmd === "quit") { await relay?.close(); await host.stop(); out({ bye: true }); process.exit(0); }
    else out({ error: `unknown cmd ${c.cmd}` });
  } catch (e) { out({ error: String(e) }); }
}
await host.stop();
process.exit(0);
