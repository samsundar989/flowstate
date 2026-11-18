// Replay a JSONL fixture over WebSocket, honouring INPUT_REQUIRED holds.
//   npm run replay -- fixtures/feature_ratelimit.jsonl --speed 8 [--port 8765] [--hold | --auto-answer-s 45] [--loop] [--quiet]
// An agent that emits status=INPUT_REQUIRED is paused (others keep going) until the headset sends
//   {"kind":"input_response","agent_id":..,"step_id":..,"option_id":..}
// or, unless --hold, the scripted responder picks default_option after --auto-answer-s real seconds.
// Headset task_assigned messages are echoed to every client as task_assigned events.
import { Hub, argOf } from "./server.ts";
import { readJsonl, resolveFixture, type AgentEvent, type ClientMessage } from "./schema.ts";

export interface ReplayOpts { port: number; speed: number; autoAnswerS: number | null; loop?: boolean; log?: boolean; tickMs?: number }

interface Lane { q: AgentEvent[]; i: number; offset: number; blocked: null | { step_id: string; since: number; timer?: NodeJS.Timeout } }

export class Replay {
  hub: Hub;
  private lanes = new Map<string, Lane>();
  private t0 = 0;
  private timer?: NodeJS.Timeout;
  private resolveDone!: () => void;
  done: Promise<void>;
  runs = 0;

  private events: AgentEvent[];
  o: ReplayOpts;
  constructor(events: AgentEvent[], o: ReplayOpts) {
    this.events = events;
    this.o = o;
    this.hub = new Hub(o.port, o.log ?? true);
    this.hub.onClient = (m) => this.onClient(m);
    this.done = new Promise((r) => (this.resolveDone = r));
  }

  private sim() { return (performance.now() - this.t0) * this.o.speed; }

  async start() {
    await this.hub.ready();
    this.begin();
  }

  private begin() {
    this.lanes.clear();
    for (const e of this.events) {
      if (!this.lanes.has(e.agent_id)) this.lanes.set(e.agent_id, { q: [], i: 0, offset: 0, blocked: null });
      this.lanes.get(e.agent_id)!.q.push(structuredClone(e));
    }
    this.hub.resetHistory();
    this.t0 = performance.now();
    this.runs++;
    this.timer = setInterval(() => this.tick(), this.o.tickMs ?? 15);
  }

  private tick() {
    const now = this.sim();
    const ready: { e: AgentEvent; t: number; lane: Lane }[] = [];
    for (const lane of this.lanes.values()) {
      while (!lane.blocked && lane.i < lane.q.length) {
        const e = lane.q[lane.i];
        const t = e.t_ms + lane.offset;
        if (t > now) break;
        lane.i++;
        ready.push({ e, t, lane });
        if (e.kind === "status" && e.status === "INPUT_REQUIRED") this.block(lane, e, now);
      }
    }
    ready.sort((a, b) => a.t - b.t);
    for (const r of ready) this.hub.send({ ...r.e, t_ms: r.t });
    if ([...this.lanes.values()].every((l) => l.i >= l.q.length)) {
      clearInterval(this.timer);
      if (this.o.loop) setTimeout(() => this.begin(), 3000);
      else this.resolveDone();
    }
  }

  private block(lane: Lane, e: AgentEvent, now: number) {
    lane.blocked = { step_id: e.step_id!, since: now };
    if (this.o.autoAnswerS != null) {
      lane.blocked.timer = setTimeout(() => this.answer(e.agent_id, e.step_id!, e.default_option ?? e.options![0].id, "scripted responder"), this.o.autoAnswerS * 1000);
    }
  }

  answer(agent_id: string, step_id: string, option_id: string, who = "headset") {
    const lane = this.lanes.get(agent_id);
    if (!lane?.blocked || lane.blocked.step_id !== step_id) return false;
    clearTimeout(lane.blocked.timer);
    lane.blocked = null;
    const next = lane.q[lane.i];
    if (next) {
      lane.offset = this.sim() + 300 - next.t_ms;
      if (next.kind === "status" && next.answer !== undefined) {
        const asked = lane.q[lane.i - 1];
        const label = asked?.options?.find((o) => o.id === option_id)?.label ?? option_id;
        next.answer = option_id;
        next.title = `${who === "headset" ? "User" : "Auto"} chose: ${label}`;
      }
    }
    return true;
  }

  private onClient(m: ClientMessage) {
    if (m.kind === "input_response") this.answer(m.agent_id, m.step_id ?? "", m.option_id);
    else this.hub.send({ t_ms: this.sim(), agent_id: m.agent_id, kind: "task_assigned", title: m.task, priority: m.priority ?? "normal", status: "SUBMITTED", ...(m.task_id ? { detail: `task_id=${m.task_id}` } : {}) });
  }

  async stop() { clearInterval(this.timer); for (const l of this.lanes.values()) clearTimeout(l.blocked?.timer); await this.hub.close(); }
}

if (process.argv[1]?.endsWith("replay.ts")) {
  const args = process.argv.slice(2);
  const file = args.find((a, i) => !a.startsWith("--") && !(args[i - 1] ?? "").match(/^--(speed|port|auto-answer-s)$/));
  if (!file) { console.error("usage: npm run replay -- <fixture.jsonl> [--speed 8] [--port 8765] [--hold|--auto-answer-s 45] [--loop] [--quiet]"); process.exit(2); }
  const path = resolveFixture(file);
  const r = new Replay(readJsonl(path), {
    port: Number(argOf(args, "port", "8765")),
    speed: Number(argOf(args, "speed", "1")),
    autoAnswerS: args.includes("--hold") ? null : Number(argOf(args, "auto-answer-s", "45")),
    loop: args.includes("--loop"),
    log: !args.includes("--quiet"),
  });
  await r.start();
  console.error(`replaying ${path} on ws://0.0.0.0:${r.hub.port} at ${r.o.speed}x`);
  await r.done;
  console.error(`replay finished: ${r.hub.history.length} events`);
  await r.stop();
}
