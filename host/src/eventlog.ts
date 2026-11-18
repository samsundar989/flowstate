// Append-only, host-wide event log. Every event is written to disk before any client sees it.
import { appendFileSync, existsSync, readFileSync } from "node:fs";
import type { HostEvent } from "./protocol.ts";

export type NewEvent = Omit<HostEvent, "type" | "seq" | "at">;

export class EventLog {
  events: HostEvent[] = [];
  private subs = new Set<(e: HostEvent) => void>();
  private file: string;
  constructor(file: string) {
    this.file = file;
    if (existsSync(file)) for (const l of readFileSync(file, "utf8").split("\n")) if (l.trim()) this.events.push(JSON.parse(l));
  }
  get head(): number { return this.events.at(-1)?.seq ?? 0; }
  append(e: NewEvent): HostEvent {
    const ev = { type: "event", seq: this.head + 1, at: new Date().toISOString(), ...e } as HostEvent;
    appendFileSync(this.file, JSON.stringify(ev) + "\n");
    this.events.push(ev);
    for (const s of this.subs) s(ev);
    return ev;
  }
  since(seq: number): HostEvent[] { return this.events.filter(e => e.seq > seq); }
  /** Replay then subscribe in the same tick, so no event can fall between them (JS is single-threaded). */
  follow(lastSeq: number, fn: (e: HostEvent) => void): () => void {
    for (const e of this.since(lastSeq)) fn(e);
    this.subs.add(fn);
    return () => this.subs.delete(fn);
  }
  openDecisions(): string[] {
    const open = new Map<string, string>(); // decision -> run
    for (const e of this.events) {
      if (e.kind === "question" && e.decision_id) open.set(e.decision_id, e.run_id ?? "");
      if (e.kind === "answered" && e.decision_id) open.delete(e.decision_id);
      if (e.kind === "finished") for (const [d, r] of open) if (r === e.run_id) open.delete(d);
    }
    return [...open.keys()];
  }
}
