# Provenance: `host/`

**Everything in `host/`, `docs/host/` and `runs/host/` is new code and text, written in this repository during the competition
window** (2026-11-13, task #783). Git history on branch `af/t783` carries the dates.

**Task #785 (host core, 2026-11-13)** added `src/state.ts`, `src/git.ts`, `src/cli.ts`, the job runner in `src/server.ts`,
resume and closing-answer handling in `src/agents.ts`, `test/core.test.ts`, `test/core_headset.ts`, `scripts/core_live.mjs`,
`scripts/restart_demo.mjs`, `scripts/make_task_fixture.ts` and the fixtures `codex_ask*.jsonl`, `codex_build.jsonl`,
`protocol/task_flow.json`, all new in this window (branch `af/t785`). Its spec was the #785 task card (states, worktree per task,
limits, CLI) and the coding CLIs' own `--help` (`exec resume`, `--output-schema`, `-c sandbox_mode`, `--resume`). No new
dependency was added. The same clean-room rule held: no autoflow source was opened.

**No autoflow source was read or copied.** I did not open any code file under `/opt/flowstate\Documents\autoflow\` or anything copied
from it. The spec was:
- the task card for #783 (written by the project Head);
- autoflow's documented behaviour as this repository records it (the task card, and `af` as a user sees it through its CLI). The
  concepts taken are the principles only: projects, a task queue, a git worktree per task, and decisions sent back to the human.
  In practice the task card was the spec. I did not need autoflow's README for this skeleton.
- the public docs and `--help` output of the two coding CLIs, plus their real headless streams captured on this machine
  (`test/fixtures/streams/`, sanitized by `scripts/sanitize_stream.mjs`);
- this repository's own event schema (`agents/bridge`), referenced only as a mapping in `docs/host/PROTOCOL.md`. No code from it is imported.

## Third-party dependencies (runtime)
Direct: `ws` 8.23.0 (MIT), `selfsigned` 5.5.0 (MIT; pulls `@peculiar/x509`, `pkijs`, `asn1js`: MIT/BSD-3-Clause), `qrcode` 1.5.4
(MIT), `bonjour-service` 1.4.4 (MIT; pulls `multicast-dns`, `dns-packet`: MIT).
The full transitive list with versions and licences is generated from `package-lock.json` into
`runs/host/skeleton/dependency_licenses.txt`: 59 runtime packages, all MIT, ISC, BSD-3-Clause, 0BSD or Apache-2.0. None is copyleft.
Dev only: `typescript` (Apache-2.0), `@types/node`, `@types/ws`, `@types/qrcode` (MIT).
Runtime: Node.js ≥ 22 (MIT), using `--experimental-strip-types`.

## Not shipped
No model weights, no credentials, and no vendor binaries. The coding CLIs are the user's own installs, started as child processes.
