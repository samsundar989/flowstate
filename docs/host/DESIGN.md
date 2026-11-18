# Flowstate host: design (skeleton, #783)

The host is one command on the user's own computer (`npm start` in `host/`; `flowstate-host` once packaged). It has no
desktop UI. It pairs with a headset over Wi-Fi, runs the user's own installed and signed-in coding CLI in a new
project folder, and streams what the agent does over an encrypted link the headset can resume. The wire contract is
`PROTOCOL.md`. Evidence for every claim below is in `runs/host/skeleton/`.

## 1. Processes and ports

```
headset (client)  ── wss://<LAN IPv4>:47821/v1 (TLS 1.2+, pinned self-signed cert) ──►  host (node, one process)
                  ◄─ mDNS _flowstate._tcp on UDP 5353 (TXT: v, fp, n) ──────────────        │
                                                                                         spawns per run
                                              agent CLI (claude -p … stream-json  │  codex exec --json -)
                                              cwd = ~/FlowstateProjects/<slug>/ (a fresh git repo)
```
- **One host process** owns the TLS port, the pairing state, the event log, and the agent children.
- **One TCP port, 47821**, bound to the LAN address only. The host picks the Wi-Fi adapter and skips virtual and VPN adapters; `--bind` overrides.
  There is no plain HTTP listener and no localhost API.
- **Agent child per run.** Each runs in its own project folder as its own process tree. On cancel or shutdown the host kills the
  whole tree (`taskkill /PID <pid> /T /F` on Windows, a process group kill elsewhere). The exit state is always reported as a
  `finished` event (`ok`, `exit_code`, `reason`, `commit`).
- **Questions to the human.** One CLI sends its permission requests over stdio to the host (`--permission-prompt-tool stdio`,
  `control_request can_use_tool`). The host turns each into a `question` event and answers through `control_response` when the
  wearer says yes or no. This was verified live: `runs/host/skeleton/claude/events.jsonl` seqs 4–8.
  The other CLI's `exec` mode has no mid-run approvals. Its sandbox runs work in the folder and makes `.git` read-only, so the host
  commits what the agent left (`(saved by host)`; verified live in `runs/host/skeleton/codex/`). Its `app-server` mode is the
  route to questions (build list, item 6).

## 2. Pairing and trust: what is stored where

| item | where | notes |
|---|---|---|
| host TLS key + self-signed cert (ECDSA P-256, 10 y) | host user config: `%APPDATA%\flowstate-host\host.key/.crt` (`~/.config/flowstate-host` elsewhere) | created on first run; key file is owner-only; **never in a repo** |
| paired devices | same folder, `devices.json` | stores `device_id`, **SHA-256 of** the device secret, name, time, source IP |
| event log | same folder, `events.jsonl` | append-only; raw agent streams under `raw/` for diagnosis |
| one-time QR token, 6-digit code | host memory only | 120 s, single use, rotated and reprinted |
| device secret + host pin (`h`,`p`,`fp`) | headset: encrypted prefs whose key lives in the **Android Keystore** | the only things the headset keeps |
| model or subscription credentials | **the CLI's own store** (its own login) | the host never reads, copies, stores or forwards them |

The wearer types at most 6 digits in VR (and only on the code path). The QR path needs no typing. Moving to the OS keychain
(Windows Credential Manager or DPAPI) for `host.key` is item 9 on the build list; user-profile ACLs protect it today.

## 3. Build list for the next tasks (the full scope)
1. Headset client (Android): NsdManager discovery, pinned `X509TrustManager`, pairing UI (QR where a camera is allowed, else the
   6-digit code + the 4-character check), resume loop with backoff, Keystore storage. Spec: PROTOCOL.md + `host/test/fixtures/protocol/`.
2. Projects: list, rename, archive; a project picker in the headset; `welcome.projects` already carries state.
3. Task queue per project: queued, running, done; one runner at a time per project, N across projects (limit by RAM).
4. A git worktree per task (`git worktree add`) so parallel tasks never share a working tree; merge on accept.
5. Results: diff summary, test output and the program's output as events; "show me" opens a file panel.
6. Decisions and questions back to the human for every CLI: stdio control for one, `app-server` JSON-RPC approvals for the
   other; free-text answers (voice) as well as Yes/No.
7. Bring-your-own API key: a one-time **host page** (local, served only on the LAN TLS port behind pairing) that stores the
   key in the OS keychain and starts the CLI with it. It is never typed in VR.
8. Voice: "new project: …" → `create_project`; on-device speech to text (the voice-C pipeline) feeds `prompt`/`answer`.
9. Hardening: OS keychain for the host key, a PAKE (e.g. CPace) for the 6-digit path, device revoke command, log rotation.
10. Packaging: one installer or `npx`, a first-run firewall explainer, a startup option.

## 3a. Host core as built (#785)
- **State is the log.** `src/state.ts` folds `events.jsonl` into projects (created, active) and tasks (queued, running, needs_answer,
  done, failed, cancelled) and refuses an illegal transition. Host-only facts (agent kind, session id, process id, open question)
  sit in `tasks/<id>.json` beside it. A restart folds the log, kills orphaned agent processes it recorded (checked by pid and
  image name), puts `running` tasks back in line (`reason: host_restarted`) and resumes their recorded session; a question that
  ended the agent's turn stays open and its answer resumes the session.
- **Worktree per task**: `.worktrees/<task>` on `task/<task>` off `main`, made when the task starts (so task 2 sees task 1's merge).
  Success: host commit with the agent's own summary as the message, `merge --ff-only`, else a merge commit, else abort and keep.
- **Questions, one mechanism for both CLIs**: the host asks the agent to end every turn with a closing answer
  `{status: done|question, summary, question, options}` (agent B enforces it with `--output-schema`; agent A's last JSON line is
  parsed). A `question` ends the run; the answer resumes **the same session** (`exec resume <id>` / `--resume <id>`). No process
  is held open while a person thinks, so the question survives drops and restarts. Agent A's live permission requests and its
  own multiple-choice tool still go over its stdio control channel while it runs.
- **Limits**: one active task per project, 2 agents in total, 30 min wall clock per run (`--per-project`, `--max-running`,
  `--task-timeout-min`). Agent B runs with `sandbox_mode=workspace-write` in the worktree (the repo's `.git` is outside it, so the
  host commits); agent A runs with `acceptEdits` and asks for anything else.
- **Command line = a paired device.** `flowstate-host new|answer|stop <task>` connect as device `d_local` (secret in the config
  folder) and send the same frames as the headset; `status` folds the log offline; `stop` ends the host (Windows: `taskkill /T`).

- **Build from nothing (#972, PROTOCOL.md section 7).** A project's first task is planned: the agent ends its first turn with
  `parts` and one design question and writes nothing; the host logs `plan` then `question`, and the answer resumes the session to
  build. The template pins a static web app (index.html, ≤ 3 files, offline, 1280×800, tap only). After each merge the host logs
  `preview` for `index.html` at main's head, and a device reads the files with `get_file`. A later `prompt` is a follow-up task in
  the same repo (no plan). The host holds no extra process: the preview is served from git on request.

## 4. Threat model (twelve lines)
1. **Stranger on the same Wi-Fi** connects: TLS works, but every frame except pairing gets `unauthorized`; no QR token and no code means no device.
2. **Guessing the code** (10^6): 5 wrong tries burn the code, 10 failures per minute lock the source for 60 s, codes live 120 s. That gives ≤ 50 guesses per code lifetime.
3. **Replay** of a pairing or resume frame: it travels only inside TLS; tokens and codes are single use; resume needs a 256-bit device secret.
4. **Token theft** (someone photographs the QR): a 120 s window, single use, and the host prints every new pairing with its source IP.
5. **Fake host / MITM on the QR path**: impossible without the host key, because the client pins `fp` from the QR before sending anything (test: wrong fingerprint refused).
6. **MITM on the code path** (TOFU from mDNS): the 4-character check mixes `fp` and the code, so a relayed attacker cert shows a different check. The residual risk is that a wearer ignores the mismatch. A PAKE removes it (build list 9).
6b. **MITM on confirm-on-computer** (PROTOCOL 2d, the Quest 2 default): the six digits are derived from the certificate each side
   actually sees and from both nonces, and the host commits to its nonce before it sees the headset's. A relay shows two different
   codes (tests: three relays with their own certificate, `confirm.test.ts`, `ConfirmPairingTest`). Forcing a match by search
   breaks the commitment, which the headset checks before showing anything. The residual risk is one blind 10^-6 guess per attempt,
   with one pending request at a time, 120 s each, and 3 refusals per source per minute. As in 6, it relies on the person at the
   computer comparing the digits rather than typing y by reflex; the prompt shows the device name and source address.
7. **Bind address**: LAN IPv4 only, never `0.0.0.0` by default; no listener on Tailscale or WSL adapters unless `--bind` asks for it.
8. **mDNS leaks** the host label, certificate fingerprint and all interface addresses (including IPv6) to the LAN. Use `--no-mdns` on untrusted networks.
9. **Agent damage**: the agent runs as the user in a fresh folder; the second CLI is sandboxed to that folder; cancel kills the whole tree; permission asks reach the human.
10. **Secrets in transit or logs**: event text is cleaned of absolute paths, key-shaped strings and vendor names; the host stores only hashes of device secrets; gitleaks finds nothing in `host/`.
   #972 keeps this for every new text field: `plan` titles, part names (≤ 24 chars) and part files go through the same `clean()`;
   `preview.path` is always `index.html`; `file_chunk.path` only echoes a request that passed the path check. The one exception is
   `file_chunk.data`: the bytes of the app the agent wrote, sent as they are tracked (the headset must run them unchanged and check
   their SHA-256). Its visible text is asked to be brand-clean by the template and checked by the gate (`verifyWebApp`), not
   rewritten by the host.
11. **Reading files through the link** (`get_file`, #972): only a paired device can ask, and only for a regular file **tracked at
   a commit on that project's `main`**, read from git's object store with `git cat-file`, never from the working folder. The path
   must be relative and plain (`..`, `.`, absolute, drive and backslash paths are refused before git sees them, `bad_path`); the
   commit must be hex (no ref names or options reach git) and an ancestor of `main` (task branches and unknown objects are
   `not_found`, as are untracked, ignored and `.git/` files, folders and symlinks); files over 512 KB are `too_large`. So the link can
   expose nothing that was not already committed to a project the host made, and nothing outside the projects folder. Tests: every
   refusal in `host/test/build_flow.test.ts`.
12. **Running what the agent built**: the preview is code from the agent. The host never runs it; the headset runs it in its preview
   window, which should have no network access and no bridge to the app (the template also forbids network use; the gate checks it).

## 5. Away from home: Tailscale (option, not required)
1. Install Tailscale on the PC and the headset (it has an Android app) and sign both into the same tailnet.
2. Start the host with `--bind <PC's 100.x Tailscale address>`, or run a second host instance on that address.
3. Pair once at home. The pin, token and device secret do not depend on the network, but the QR carries the address, so pair against the address you will use.
4. mDNS does not cross the tailnet, so use the QR or enter the host's 100.x address once.
5. Traffic is then WireGuard-encrypted end to end *and* TLS-pinned; no port is opened on the router.

## 6. Driving the CLIs from another program: what the terms say
Fetched 2026-11-13. Quotes are verbatim unless marked.
- Anthropic, Claude Code legal and compliance (https://code.claude.com/docs/en/legal-and-compliance):
  "Anthropic does not permit third-party developers to offer Claude.ai login into their own applications, or to route requests
  through Free, Pro, Max, or Ultra plan credentials on behalf of their users." · "developers may not collect, store, or
  intermediate Claude.ai credentials or session tokens — sign-in to a Claude account must complete through Anthropic's own flow." ·
  "Nor does it prevent an end user from signing in to the unmodified Claude Code binary with their own Claude subscription".
- Anthropic, Agent SDK overview (https://code.claude.com/docs/en/agent-sdk/overview): "Unless previously approved, Anthropic does
  not allow third party developers to offer claude.ai login or rate limits for their products, including agents built on the
  Claude Agent SDK." · and, on driving the CLI: "run the CLI as a subprocess with the `-p` flag and `--output-format json`."
- OpenAI, Codex non-interactive mode (https://developers.openai.com/codex/noninteractive → learn.chatgpt.com/docs/non-interactive-mode):
  "Non-interactive mode lets you run Codex from scripts". The auth page (https://developers.openai.com/codex/auth) says, in
  summary rather than verbatim: use an API key for programmatic workflows such as CI; access tokens from a ChatGPT login are
  intended for trusted scripts and private runners. The help-center plan page returned 403 and was not read.

**Verdict.** The design stays inside what is written. The host is software the user runs on their own machine. It launches the
**unmodified** CLI that the user installed and signed into through the vendor's own flow. It never offers a login, and never
sees, stores or routes a credential. Neither vendor addresses this case word for word. The Anthropic text forbids
*third-party products* from offering or routing subscription login. If Flowstate is distributed to other people who run it on
their own subscriptions, that reading needs a decision. The safe path is that bring-your-own API key (build list 7) is the
documented default for distribution, and that a user's own login is used for personal use. **This goes to developer.**
