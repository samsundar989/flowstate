// WebSocket hub shared by replay and live modes: broadcasts events, replays history to late joiners,
// validates headset messages and hands them to a handler.
import { WebSocketServer, WebSocket } from "ws";
import { validateClientMessage, validateEvent, type AgentEvent, type ClientMessage } from "./schema.ts";

export class Hub {
  wss: WebSocketServer;
  history: AgentEvent[] = [];
  onClient: (m: ClientMessage) => void = () => {};
  private lastT = 0;
  port: number;
  private log: boolean;
  constructor(port: number, log = true) {
    this.port = port;
    this.log = log;
    this.wss = new WebSocketServer({ port });
    this.wss.on("connection", (ws) => {
      for (const e of this.history) ws.send(JSON.stringify(e));
      ws.on("message", (buf) => {
        let m: unknown;
        try { m = JSON.parse(buf.toString()); } catch { return this.reject(ws, "message is not JSON"); }
        const err = validateClientMessage(m);
        if (err) return this.reject(ws, err);
        if (this.log) console.error(`<- ${JSON.stringify(m)}`);
        this.onClient(m as ClientMessage);
      });
    });
  }
  ready(): Promise<void> {
    return new Promise((r) => (this.wss.address() ? r() : this.wss.once("listening", () => r())));
  }
  /** Broadcast one event; clamps t_ms so the wire stream is non-decreasing. */
  send(e: AgentEvent) {
    e.t_ms = Math.max(this.lastT, Math.round(e.t_ms));
    this.lastT = e.t_ms;
    const err = validateEvent(e);
    if (err) throw new Error(`refusing to send invalid event: ${err} ${JSON.stringify(e)}`);
    this.history.push(e);
    const s = JSON.stringify(e);
    if (this.log) console.log(s);
    for (const c of this.wss.clients) if (c.readyState === WebSocket.OPEN) c.send(s);
  }
  resetHistory() { this.history = []; this.lastT = 0; }
  private reject(ws: WebSocket, why: string) {
    ws.send(JSON.stringify({ t_ms: this.lastT, agent_id: "bridge", kind: "error", title: "Rejected client message", detail: why }));
  }
  close(): Promise<void> {
    for (const c of this.wss.clients) c.terminate();
    return new Promise((r) => this.wss.close(() => r()));
  }
}

export function argOf(args: string[], name: string, def?: string): string | undefined {
  const i = args.indexOf(`--${name}`);
  return i >= 0 ? args[i + 1] : def;
}
