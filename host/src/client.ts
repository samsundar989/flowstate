// Reference client (what the headset does), used by tests and test/fake_headset.ts.
// Pins the host certificate by SHA-256 of its DER bytes BEFORE any byte of the WebSocket upgrade is sent.
import { connect as tlsConnect, type TLSSocket } from "node:tls";
import { createHash } from "node:crypto";
import WebSocket from "ws";
import { validate, type HostEvent } from "./protocol.ts";

export class PinMismatch extends Error {}

export function pinnedSocket(host: string, port: number, fp: string): Promise<TLSSocket> {
  return new Promise((res, rej) => {
    const s = tlsConnect({ host, port, rejectUnauthorized: false, servername: undefined as any, ALPNProtocols: ["http/1.1"] });
    s.once("error", rej);
    s.once("secureConnect", () => {
      const seen = createHash("sha256").update(s.getPeerCertificate().raw).digest("hex");
      if (seen !== fp) { s.destroy(); return rej(new PinMismatch(`certificate ${seen.slice(0, 12)}… does not match the pinned ${fp.slice(0, 12)}…`)); }
      res(s);
    });
  });
}

export class HeadsetClient {
  ws!: WebSocket; frames: any[] = []; events: HostEvent[] = []; lastSeq = 0; closed = false; closeCode = 0;
  private waiters: { pred: (f: any) => boolean; res: (f: any) => void; rej: (e: Error) => void }[] = [];
  host: string; port: number; fp: string;
  constructor(host: string, port: number, fp: string) { this.host = host; this.port = port; this.fp = fp; }

  async open(): Promise<this> {
    const sock = await pinnedSocket(this.host, this.port, this.fp);
    this.ws = new WebSocket(`wss://${this.host}:${this.port}/v1`, { createConnection: () => sock } as any);
    this.closed = false;
    this.ws.on("message", d => {
      const f = JSON.parse(String(d)); const bad = validate(f, "h2c");
      if (bad) throw new Error(`host sent an invalid frame: ${bad}`);
      this.frames.push(f);
      if (f.type === "event") { if (f.seq <= this.lastSeq) return; this.events.push(f); this.lastSeq = f.seq; }
      for (const w of [...this.waiters]) if (w.pred(f)) { this.waiters.splice(this.waiters.indexOf(w), 1); w.res(f); }
    });
    this.ws.on("close", c => { this.closed = true; this.closeCode = c; for (const w of this.waiters.splice(0)) w.rej(new Error(`closed ${c}`)); });
    await new Promise<void>((res, rej) => { this.ws.once("open", () => res()); this.ws.once("error", rej); });
    return this;
  }
  send(f: object) { const bad = validate(f, "c2h"); if (bad) throw new Error(`client frame invalid: ${bad}`); this.ws.send(JSON.stringify(f)); }
  waitFor(pred: (f: any) => boolean, ms = 30000): Promise<any> {
    const have = this.frames.find(pred); if (have) { this.frames.splice(this.frames.indexOf(have), 1); return Promise.resolve(have); }
    return new Promise((res, rej) => {
      const t = setTimeout(() => rej(new Error("timeout waiting for frame")), ms);
      this.waiters.push({ pred, res: f => { clearTimeout(t); this.frames.splice(this.frames.indexOf(f), 1); res(f); }, rej: e => { clearTimeout(t); rej(e); } });
    });
  }
  /** Simulate a Wi-Fi drop: kill the TCP connection without a close handshake. */
  drop() { (this.ws as any)._socket?.destroy(); }
  close() { this.ws?.close(); }
}
