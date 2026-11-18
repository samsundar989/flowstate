// Fake headset for the core end-to-end: from the QR text alone, pair, say "new project: …" with a prompt that forces
// a question, answer it like the wearer would, follow the task to its end. Times the answer -> agent-resumed round trip.
// CLI: node core_headset.ts --uri "<flowstate://pair?...>" --prompt "<text>" [--prefer python] [--out summary.json]
import { writeFileSync } from "node:fs";
import { HeadsetClient } from "../src/client.ts";
import { parsePairUri } from "../src/protocol.ts";

const a = (k: string) => { const i = process.argv.indexOf(`--${k}`); return i > 0 ? process.argv[i + 1] : undefined; };
const q = parsePairUri(a("uri")!);
const say = (s: string) => console.log(`${new Date().toISOString()} ${s}`);
const pc = await new HeadsetClient(q.h, q.p, q.fp).open();
pc.send({ type: "pair_token", token: q.t, device_name: "Test headset" });
const p = await pc.waitFor(f => f.type === "paired" || f.type === "error");
if (p.type !== "paired") throw new Error(`pairing refused: ${p.code}`);
pc.close();
const c = await new HeadsetClient(q.h, q.p, q.fp).open();
c.send({ type: "resume", device_id: p.device_id, device_secret: p.device_secret, last_seq: 0 });
await c.waitFor(f => f.type === "welcome");
say(`paired and resumed over ${q.h}:${q.p}`);
c.send({ type: "create_project", req_id: "c1", prompt: a("prompt")! });
const ok = await c.waitFor(f => (f.type === "ok" && f.req_id === "c1") || f.type === "error");
if (ok.type !== "ok") throw new Error(`refused: ${ok.code}`);
const res: any = { host: `${q.h}:${q.p}`, project_id: ok.project_id, task_id: ok.task_id, events: [] as any[] };
let tAnswer = 0, end: any;
const deadline = Date.now() + 20 * 60_000;
while (!end && Date.now() < deadline) {
  const f = await c.waitFor(x => x.type === "event", deadline - Date.now());
  if (f.task_id !== ok.task_id && f.project_id !== ok.project_id) continue;
  res.events.push(f);
  say(`#${f.seq} ${f.kind}${f.state ? ` ${f.state}` : ""}: ${f.title}`);
  if (f.kind === "question") {
    const want = new RegExp(a("prefer") ?? "python", "i");
    const opt = f.options.find((o: any) => want.test(o.label)) ?? f.options[0];
    res.question = { title: f.title, options: f.options, chose: opt };
    await new Promise(r => setTimeout(r, 1000)); // the wearer reads it
    tAnswer = Date.now(); res.answer_sent_at = new Date(tAnswer).toISOString();
    c.send({ type: "answer", req_id: "a1", decision_id: f.decision_id, option_id: opt.id });
    say(`answered "${opt.label}"`);
  }
  if (f.kind === "started" && tAnswer && res.decision_roundtrip_s === undefined) {
    res.decision_roundtrip_s = (Date.now() - tAnswer) / 1000; res.resumed_at = new Date().toISOString();
  }
  if (f.kind === "finished") res.finished = f;
  if (f.kind === "task_state" && ["done", "failed", "cancelled"].includes(f.state)) end = f;
}
res.final_state = end?.state ?? "timeout";
c.close();
console.log(JSON.stringify({ ...res, events: res.events.length }, null, 1));
if (a("out")) writeFileSync(a("out")!, JSON.stringify(res, null, 1));
process.exit(end?.state === "done" ? 0 : 1);
