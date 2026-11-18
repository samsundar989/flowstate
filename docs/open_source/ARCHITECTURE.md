# Architecture (one page)

> Rewritten by t906 (2026-11-13) for main `60655983`, replacing t779's draft, which described the older `agents/bridge` Live
> path. Sources are named in brackets. Security claims and their sources: `SECURITY.md`. Install: [INSTALL](../../INSTALL.md).
> **Worn status:** the host path has run on a Quest 2 lying asleep on a desk with injected pinches and audio (#814, #815,
> #862), not yet on a person.

```
 YOUR COMPUTER (Windows 11 tested)                               HEADSET (Quest 2 tested; Pro / 3 / 3S targeted)
 ┌──────────────────────────────────────────────┐               ┌───────────────────────────────────────────────┐
 │ host/  (Node 22, TypeScript, no window)       │  mDNS         │ native/overhand (Kotlin, Meta Spatial SDK)     │
 │  main.ts    LAN address, terminal, mDNS       │ _flowstate.   │  hostlink/  HostDiscovery (NsdManager)         │
 │  server.ts  TLS WebSocket :47821 /v1,         │ _tcp ───────▶ │             HostLink: pinned TLS, pairing,     │
 │             pairing, job runner               │               │             resume loop with backoff           │
 │  pairing_confirm.ts  six-digit confirm        │  wss://, cert │             PairedHostStore (Android Keystore) │
 │  pairing.ts  typed code / token fallback      │  pinned by    │  features/connect/  Connect panel (Settings)   │
 │  eventlog.ts append-only events.jsonl         │◀────────────▶ │  features/project/  New project chip, project  │
 │  state.ts   tasks folded from the log         │  SHA-256      │             window, questions as decision cards│
 │  agents.ts  starts YOUR coding CLI ──┐        │               │  voice/     on-device speech (while worn, unmuted)│
 │  git.ts     repo per project,        │        │               │  assets/judge/** recorded day (no network)     │
 │             worktree per task        ▼        │               └───────────────────────────────────────────────┘
 │  coding CLI you installed and signed in to (its own login / API key)│
 │  ~/FlowstateProjects/<name>  (git, main + .worktrees/<task>)        │
 └──────────────────────────────────────────────────────────────────────┘
```

## What runs where

| Where | What | Source |
|---|---|---|
| Computer | `host/`: one process, started with `npm start`; terminal only, no window | `host/src/main.ts`, `host/package.json` |
| Computer | Your coding agent: the CLI you installed and signed in to (agent A `claude` or agent B `codex`), started by the host as a child process in a task's worktree. All model calls are made by that CLI with **its own login or API key**; the host passes no credentials | `host/src/agents.ts` `startRun`, `agentAvailable` |
| Computer | Your projects, one git repository each, `main` plus a worktree and branch `task/<id>` per task | `host/src/git.ts` |
| Headset | The app. **Recorded** mode (default) replays a bundled day and needs no computer or network. Once paired, `HostSource` replaces the Live source and the app talks only to the host | `docs/host/CLIENT.md` "Wiring"; #814 report |
| Headset | Speech to text runs on the headset (bundled recogniser); only the confirmed text is sent | #815 report; `docs/testing/host_gate_runsheet.md` steps 4-5 |
| Nowhere | No server of ours, no relay, no account. Nothing leaves your LAN except what your own coding CLI sends to its vendor | `host/src/main.ts` (binds a LAN address), `SECURITY.md` S1 |

## How pairing works

1. **Discovery.** The host advertises `_flowstate._tcp` on the LAN with its certificate fingerprint and computer name. The
   headset's Connect panel lists what it finds (`main.ts` `mdns.publish`; `hostlink/HostDiscovery.kt`).
2. **Pinned TLS.** The headset opens `wss://<address>:47821/v1` and accepts only a certificate whose SHA-256 equals the
   fingerprint it is pairing with (`HostLink.kt` `PinningTrustManager`; `host/src/client.ts` is the reference client).
3. **Six digits, confirmed on the computer.** The headset sends a fresh device key; the host commits to a nonce; the headset
   sends its nonce; the host reveals its own. Both compute six digits from the certificate each actually saw, both keys and
   both nonces. The headset shows "Confirm 123 456 on your computer", the terminal prints `Code 123 456. Type y to allow`, and
   the person types `y`. A relay with its own certificate makes the digits differ (`host/src/pairing_confirm.ts`;
   `docs/host/PROTOCOL.md` §2d; tests in `host/test/confirm.test.ts`).
4. **Device secret.** On `y`, the host issues a random device secret, stores only its hash, and the headset stores the secret
   and the fingerprint encrypted under an Android Keystore key (`pairing_confirm.ts` `decide`; `hostlink/PairedHostStore.kt`).
5. **Every later connection** is `resume{device_id, secret, last_seq}`: the host answers `welcome` with the open questions and
   replays every event after `last_seq`, then streams live. Wi-Fi drops reconnect with backoff and lose nothing (measured on
   the device: median reconnect 6.39 s, 0 lost events over 4 drops; #814, `runs/host/connect_device/result.json`).

Fallback: a six-digit code typed on the headset keypad (single use, 120 s, rate limited), for a host without the confirm
(`host/src/pairing.ts`, `PROTOCOL.md` §2b).

## What happens to a request

1. Voice (listening while worn and unmuted): "New project, …". The app shows the transcript as a chip ("Build: … Start?"); nothing is sent until
   the wearer pinches **Start** or says "yes" (#815 report; run sheet step 5).
2. The headset sends `create_project`; a resend after a drop with the same `req_id` returns the same project instead of a
   second one (`server.ts` `handle`; test `a resent create_project (same req_id) …` in `host/test/voice_project.test.ts`).
3. The host makes `~/FlowstateProjects/<name>`, queues a task (one agent per project, two in total, 30 min cap by default),
   creates the worktree and starts your CLI there (`server.ts` `pump`, `startTask`).
4. The CLI's stream becomes events (`started`, `file_written`, `command_run`, `message`, `question`, `finished`), cleaned of
   paths, key-shaped strings and vendor names, written to `events.jsonl` before anyone sees them (`agents.ts`, `eventlog.ts`).
5. Questions: a permission request or the agent's own question becomes a card on the headset (Yes/No for permissions, the agent's own options for its questions); the answer goes back to
   the same session, or resumes it (`agents.ts` `answer`; `server.ts` `handle` "answer"; tests in `host/test/core.test.ts`).
   **On the device, the question card has not run yet** (#862 report: `host_question_answered_device=0`).
6. Success: the host commits what the agent left, merges into `main`, removes the worktree, and reports the commit and the
   program's first output line. Failure, cancel, timeout or a conflict leave `main` untouched and keep the worktree
   (`server.ts` `finish`; `git.ts` `mergeTask`). On the device, 2 of 3 spoken projects ended in a commit whose program runs (#815,
   `runs/host/voice_project/device.json`); after #862's fix, the first request after connecting reached the host 2 of 2 times
   (`runs/host/first_request/run_A_try*/device.json`).

## What is stored

Computer: the config folder (`%APPDATA%\flowstate-host`) holds the host key and certificate, paired devices (hashed
secrets), the event log, raw agent streams, task files; projects are in `~/FlowstateProjects`. Headset: only the encrypted
paired record. Full table: `SECURITY.md` "What is stored, and where".

## Other pieces in the repository

- **Recorded mode** (`native/overhand/app/src/main/assets/judge/**`): the bundled day judges see; no network at all.
- **`agents/bridge/`**: the older converter and WebSocket hub (plain `ws://`, no authentication). It still produces the
  recorded fixtures and tests; it is **not** the Live path any more ([INSTALL](../../INSTALL.md) §3).
- **`web/agentdeck/`**: the same recorded experience as a WebXR site, kept as a frozen fallback.
- **Wire contract:** `docs/host/PROTOCOL.md` with fixtures in `host/test/fixtures/protocol/`; design and build list:
  `docs/host/DESIGN.md`; headset client: `docs/host/CLIENT.md`.
