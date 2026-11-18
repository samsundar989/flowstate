// Fake headset: does what the real client will do, from the QR text alone.
// scan QR text -> pin -> pair -> "new project: ..." -> events until finished, with one mid-run Wi-Fi drop + resume,
// answers every question "allow", then (optionally, same machine) checks the folder holds working code and a commit.
// CLI: node fake_headset.ts --uri "<flowstate://pair?...>" [--projects <root>] [--out summary.json] [--drop-after 2]
import { writeFileSync } from "node:fs";
import { join } from "node:path";
import { spawnSync } from "node:child_process";
import { HeadsetClient } from "../src/client.ts";
import { parsePairUri, checkCode } from "../src/protocol.ts";

export interface FakeResult {
  host: string; paired: boolean; check: string; check_matches: boolean; project_id?: string; first_event_s?: number;
  drops: number; events: number; seqs_missing: number[]; seqs_duplicated: number[]; resume_lost_events: number;
  questions: number; answered: number; finished?: any; total_s: number; folder?: { git_log: string; program_output: string; ok: boolean };
}

export async function runFakeHeadset(o: { uri: string; prompt?: string; dropAfter?: number; projectsRoot?: string; timeoutMs?: number; log?: (s: string) => void }): Promise<FakeResult> {
  const say = o.log ?? (() => {});
  const q = parsePairUri(o.uri);                                // "scan" the QR
  const t0 = Date.now();
  let c = await new HeadsetClient(q.h, q.p, q.fp).open();      // pinned TLS
  c.send({ type: "pair_token", token: q.t, device_name: "Test headset" });
  const paired = await c.waitFor(f => f.type === "paired" || f.type === "error");
  if (paired.type !== "paired") throw new Error(`pairing refused: ${paired.code}`);
  const check = checkCode(q.fp, q.t);
  say(`paired ${paired.device_id}, check ${paired.check} (mine ${check})`);
  c.close();
  // Every session after pairing is a resume.
  const all: any[] = []; const seen = new Map<number, number>();
  let lastSeq = 0, drops = 0, questions = 0, answered = 0;
  const attach = async () => {
    const n = await new HeadsetClient(q.h, q.p, q.fp).open();
    n.lastSeq = lastSeq;
    n.send({ type: "resume", device_id: paired.device_id, device_secret: paired.device_secret, last_seq: lastSeq });
    await n.waitFor(f => f.type === "welcome");
    return n;
  };
  c = await attach();
  const baseSeq = 0; // first resume asks for the whole log, so every seq up to the end must arrive
  const tSend = Date.now();
  c.send({ type: "create_project", req_id: "c1", prompt: o.prompt ?? "new project: a hello-world CLI that prints the time", agent: "auto" });
  const ok = await c.waitFor(f => (f.type === "ok" && f.req_id === "c1") || f.type === "error");
  if (ok.type !== "ok") throw new Error(`create refused: ${ok.code}`);
  const pid = ok.project_id;
  let firstEvent: number | undefined, finished: any, ended = false;
  const deadline = Date.now() + (o.timeoutMs ?? 300_000);
  while (!ended && Date.now() < deadline) {
    let f: any;
    try { f = await c.waitFor(x => x.type === "event", Math.max(1000, deadline - Date.now())); }
    catch (e) { if (!c.closed) throw e; say("socket closed; resuming"); c = await attach(); continue; }
    seen.set(f.seq, (seen.get(f.seq) ?? 0) + 1); all.push(f); lastSeq = Math.max(lastSeq, f.seq);
    if (f.project_id !== pid) continue;
    firstEvent ??= (Date.now() - tSend) / 1000;
    say(`#${f.seq} ${f.kind}: ${f.title}`);
    if (f.kind === "question") {
      questions++;
      c.send({ type: "answer", req_id: `a${f.seq}`, decision_id: f.decision_id, option_id: "allow" }); answered++;
    }
    if (f.kind === "finished") { finished = f; if (!f.task_id) ended = true; }
    if (f.kind === "task_state" && ["done", "failed", "cancelled"].includes(f.state)) ended = true;
    if (drops === 0 && all.filter(e => e.project_id === pid).length === (o.dropAfter ?? 2)) {
      drops++; say("-- dropping the socket (Wi-Fi drop) --"); c.drop();
      await new Promise(r => setTimeout(r, 300));
      c = await attach();
    }
  }
  c.close();
  const mine = all.filter(e => e.project_id === pid).map(e => e.seq);
  const lo = Math.min(...mine), hi = Math.max(...mine);
  const missing: number[] = [];
  const projSeqs = new Set(mine);
  for (let s = baseSeq + 1; s <= hi; s++) if (!seen.has(s)) missing.push(s);
  const dup = [...seen].filter(([, n]) => n > 1).map(([s]) => s);
  const res: FakeResult = { host: `${q.h}:${q.p}`, paired: true, check: paired.check, check_matches: paired.check === check, project_id: pid,
    first_event_s: firstEvent, drops, events: projSeqs.size, seqs_missing: missing, seqs_duplicated: dup, resume_lost_events: missing.length,
    questions, answered, finished, total_s: (Date.now() - t0) / 1000 };
  if (o.projectsRoot && finished) {
    const dir = join(o.projectsRoot, pid.replace(/^p_/, ""));
    const log = spawnSync("git", ["log", "--stat", "--format=%h %s"], { cwd: dir, encoding: "utf8" }).stdout.trim();
    const entry = ["hello.js", "index.js", "main.js", "cli.js", "hello.py", "main.py"].find(f => finished.files?.includes(f)) ?? finished.files?.find((f: string) => /\.(js|py)$/.test(f));
    const exe = entry?.endsWith(".py") ? (process.platform === "win32" ? "python" : "python3") : "node";
    const run = entry ? spawnSync(exe, [entry], { cwd: dir, encoding: "utf8", timeout: 10000 }) : undefined;
    const out = (run?.stdout ?? "").trim();
    res.folder = { git_log: log, program_output: out, ok: !!log && run?.status === 0 && /\d{1,2}:\d{2}/.test(out) };
  }
  void lo;
  return res;
}

if (process.argv[1]?.endsWith("fake_headset.ts")) {
  const a = (k: string) => { const i = process.argv.indexOf(`--${k}`); return i > 0 ? process.argv[i + 1] : undefined; };
  runFakeHeadset({ uri: a("uri")!, projectsRoot: a("projects"), dropAfter: Number(a("drop-after") ?? 2), log: s => console.log(s) })
    .then(r => { console.log(JSON.stringify(r, null, 1)); if (a("out")) writeFileSync(a("out")!, JSON.stringify(r, null, 1)); process.exit(r.resume_lost_events === 0 && r.finished?.ok ? 0 : 1); })
    .catch(e => { console.error(String(e)); process.exit(1); });
}
