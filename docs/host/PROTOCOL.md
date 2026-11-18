# Flowstate host wire protocol, v1

> **Changes in #972 (build from nothing), all additive: section 7.** New event kinds `plan` and `preview`; `file_written` may
> carry `part_id`; new frames `get_file` (c2h) and `file_chunk` (h2c); new error codes `bad_path`, `not_found`, `too_large`.
> A client that ignores unknown event kinds keeps working. Fixture: `host/test/fixtures/protocol/build_flow.json`, recorded
> from a real host run.

> **Changes in #785 (tasks), all additive: a client built against the #783 fixtures still parses every frame.**
> 1. Work is now a **task** inside a project. Task events carry `task_id`; new event kinds `project_created` and `task_state`
>    (`queued`, `running`, `needs_answer`, `done`, `failed`, `cancelled`). See section 5a.
> 2. `ok` carries `task_id` for `create_project` and `prompt`. `prompt` now **queues a new task** in the project (it no longer
>    types into a running agent).
> 3. `question` may have any options (`o1`, `o2`, … with the agent's own labels), not only Yes/No, and `allow_text: true` when a
>    spoken free-text answer is accepted. `answer` takes `option_id` **or** `text` (or both); the `answered` event echoes `text`.
> 4. `finished` adds `merged`, `branch`, and `worktree` (relative to the project, present when the work was kept, not merged).
> 5. `cancel` takes an optional `task_id`. `welcome.projects[]` is `{project_id, name, state: created|active, tasks: [{task_id, state, title}]}`.
> 6. Events caused by a request can arrive **before** its `ok` (the host logs and streams them at once). Match on ids, not on order.
> Fixture: `host/test/fixtures/protocol/task_flow.json`, recorded from a real host run.

This is the contract between the **host** (one command on the user's PC) and the **headset client**.
Both ends are built and tested against the JSON fixtures in `host/test/fixtures/protocol/`. Each fixture
is one exchange: a `frames` list in order, each tagged `"dir": "c2h"` (client to host) or `"h2c"`.
`host/src/protocol.ts` validates every frame, and `host/test/protocol.test.ts` runs every fixture
through it. A client written in another language should load the same files in its own tests.

## 1. Transport

- **WebSocket over TLS** (`wss://<addr>:<port>/v1`). The default port is **47821**. There is one port and no plain listener.
- The host binds to its **LAN address only** (it picks the Wi-Fi or Ethernet address; use `--bind` to override). It never binds `0.0.0.0` unless asked to.
- The certificate is **self-signed** (ECDSA P-256). The host generates it on first run and keeps it in the user's config folder.
  There is no certificate authority. The client **pins** the certificate:
  `fp` = lowercase hex SHA-256 of the leaf certificate's DER bytes (64 hex chars). The client accepts the TLS connection
  only if the leaf's `fp` equals the pinned value. It ignores the CA chain and the hostname, since the host is reached by IP.
  On Android that means a custom `X509TrustManager` that compares `sha256(chain[0].encoded)` and nothing else.
  Cleartext stays blocked, and no `network_security_config` exception is needed.
- Frames are UTF-8 JSON text messages, one object per message. Every frame has a `type`.
  The host closes the connection on invalid JSON or on an unknown `type` before auth (close code 4400).
- An unauthenticated connection must send `pair_token`, `pair_code` or `resume` within **10 s**, or it is closed (4401).
- Keepalive: WebSocket ping every 15 s from the host. A client that misses 2 pings should reconnect with `resume`.

## 2. Pairing

### 2a. QR (headsets that can read one)
The host prints a QR code in its terminal. Its text is a URI:

```
flowstate://pair?v=1&h=192.0.2.10&p=47821&fp=<64 hex>&t=<token>&n=<host label>
```

| field | meaning |
|---|---|
| `h`, `p` | LAN address and port |
| `fp` | certificate pin (above) |
| `t` | one-time token: 22 chars base64url (128 bits), **valid 120 s, single use** |
| `n` | host label for the UI (the computer name, ≤ 32 chars) |

The client connects with the pin and sends `pair_token`. The host answers `paired` or `error`.

### 2b. Code (headsets with no camera access for apps, e.g. Quest 2)
1. The client finds hosts by **mDNS / DNS-SD**: service type `_flowstate._tcp`. The TXT record holds `v=1`, `fp=<64 hex>` and `n=<label>`.
   The address and port come from the SRV and A records. The wearer picks a host from the list.
2. The client connects, pinning the `fp` from TXT. This is trust-on-first-use, and step 4 confirms it.
3. The wearer types the **6-digit code** printed under the QR, and the client sends `pair_code`. A code is **valid 120 s and single use**.
   After **5 wrong codes** the current code is burned and a new one is printed. A source address gets **10 failures per minute**, then
   `rate_limited` for 60 s.
4. **Check**: both sides show the same 4 characters,
   `check = base32(SHA-256("flowstate-check|" + fp + "|" + secret))[0..4]`. Here `secret` is the code typed, or the token for the QR path.
   The alphabet is `ABCDEFGHJKLMNPQRSTUVWXYZ23456789` (32 symbols, no I or O; character i = alphabet[digest[i] & 31] for i = 0..3).
   The host prints it next to the code. The client computes it from the `fp` it actually saw and shows it. If they differ, the wearer
   presses "not my computer" and the client deletes the pairing and closes.

### 2d. Confirm on the computer (no typing on the headset; preferred on Quest 2)
The headset finds the host by mDNS as in 2b (a host that offers this advertises TXT `pair=confirm1`), pins the `fp` from TXT,
and nobody types anything on it. The headset shows six digits; the person at the computer checks they are the same and types
`y`. This is the numeric comparison of Bluetooth LE Secure Connections (Core spec v5.x Vol 3 Part H 2.3.5.6.2: commitment `f4`,
digits `g2 mod 10^6`) with the TLS certificate in place of the host's public key.

| # | dir | frame | |
|---|---|---|---|
| 1 | c2h | `pair_request{device_pub, device_name}` | `device_pub`: a fresh P-256 public key, base64url SPKI DER |
| 2 | h2c | `pair_commit{c}` | host picks `Na` (16 random bytes, base64url) and sends only `c = hex SHA-256("flowstate-commit\|1\|" fp "\|" device_pub "\|" Na)` |
| 3 | c2h | `pair_nonce{nonce}` | `Nb`, 16 random bytes, base64url; sent only after `c` arrived |
| 4 | h2c | `pair_reveal{nonce}` | `Na`, sent only after `Nb` arrived |
| 5 | h2c | `pair_pending{digits_hint:"none"}` | the host never sends the digits; the headset computes them |
| 6 | h2c | `paired{…, check}` or `pair_refused{reason}` | `paired` only after the person typed `y`; `check = checkCode(fp, digits)` |

Both sides compute `digits = (uint32_be(SHA-256("flowstate-digits|1|" fp "|" device_pub "|" Na "|" Nb)[0..4]) mod 10^6)`, 6 digits
with leading zeros. **`fp` is the host's own certificate fingerprint on the host, and on the headset the fingerprint it actually
saw in the TLS handshake.** The headset checks that `Na` opens `c` (with the `fp` it saw) before it shows anything; if not, it
stops with "This is not the computer it claims to be".

The host prints `Pair "<device_name>" (<address>)? Code 123 456. Type y to allow`. Any other answer refuses.
Rules: **one pending request** on the host at a time (`busy` for the rest); a request **expires 120 s** after `pair_request`;
**3 refusals per source address per minute**, then `rate_limited` without asking anyone; nothing is stored or issued before `y`.
The 10 s auth timer is replaced by the 120 s expiry once `pair_request` is accepted. `pair_nonce` is accepted once, only on the
connection holding the pending request; anything out of order ends the attempt with `out_of_order` and no reveal.

`pair_refused.reason`: `declined` (typed n), `expired`, `busy`, `rate_limited`, `out_of_order`. The socket is then closed (4401).

**Why the digits authenticate both ends.** mDNS is unauthenticated, so an attacker on the Wi-Fi can answer with its own address
and certificate. The headset's digits use the attacker's fingerprint, the host's use its own, so a relay shows two different
codes. To make them match anyway the attacker would have to pick a nonce after seeing the other side's, and the order forbids it:
toward the host it must send `Nb'` before `Na` is revealed; toward the headset it must commit to `Na'` before it gets `Nb`. One
attempt succeeds with probability 10^-6, and attempts are limited by the rules above. Tests: `host/test/confirm.test.ts` and
`ConfirmPairingTest` run three relays with their own certificate (transparent, active, and one that grinds about 10^6 nonces to
force matching digits: the headset's commitment check catches it).

**Versioning.** This is an addition to v1: old clients keep using `pair_code` and `pair_token` unchanged. A host from before it
answers `pair_request` with `error bad_request` and closes 4400; the client then offers the typed code (2b).
Fixtures: `pair_confirm.json` (with `inputs` that recompute `c`, the digits and `check`) and `pair_confirm_refused.json`.

### 2c. After pairing
`paired` carries `device_id` and `device_secret` (32 bytes, base64url). The client stores the secret in the **Android Keystore**
(an encrypted preference whose key is held in the Keystore) together with `h`, `p` and `fp`. The host stores only
`SHA-256(device_secret)` in `devices.json` in its config folder. Every later connection is a `resume`.

## 3. Client to host frames

| type | fields | answer |
|---|---|---|
| `pair_token` | `token`, `device_name` | `paired` or `error` |
| `pair_code` | `code` (6 digits as a string), `device_name` | `paired` or `error` |
| `pair_request` | `device_pub`, `device_name` | `pair_commit`, or `pair_refused` (2d) |
| `pair_nonce` | `nonce` (16 bytes base64url) | `pair_reveal`, `pair_pending`, then `paired` or `pair_refused` |
| `resume` | `device_id`, `device_secret`, `last_seq` (0 = everything) | `welcome`, then replayed `event`s, then live events |
| `create_project` | `req_id`, `prompt`, optional `name`, optional `agent` (`"auto"` default) | `ok` with `project_id` and `task_id`, then events. This is "new project by voice". |
| `prompt` | `req_id`, `project_id`, `text` | `ok` with `task_id`; a new task is queued in the project |
| `answer` | `req_id`, `decision_id`, `option_id` and/or `text` (`text` only where the question has `allow_text`) | `ok`, then an `answered` event; `error` `unknown_decision` if it is not open or the option is not one of its options |
| `cancel` | `req_id`, `project_id`, optional `task_id` (default: the project's active task, else its oldest queued one) | `ok`; the whole agent process tree is killed; `finished` with `ok:false, reason:"canceled"`, then `task_state cancelled` |
| `get_file` | `req_id`, `project_id`, `commit`, `path`, `offset` (#972, section 7e) | `file_chunk`, or `error` `bad_path` / `not_found` / `too_large` / `unknown_project` / `bad_request` |

Only `pair_token`, `pair_code`, `pair_request`, `pair_nonce` and `resume` are allowed before auth. Any other frame gets `error` `unauthorized` and is closed (4401).

## 4. Host to client frames

| type | fields |
|---|---|
| `paired` | `device_id`, `device_secret`, `host_name`, `check` |
| `pair_commit` | `c` (64 hex) |
| `pair_reveal` | `nonce` |
| `pair_pending` | `digits_hint`: always `"none"` |
| `pair_refused` | `reason`: `declined`, `expired`, `busy`, `rate_limited`, `out_of_order` |
| `welcome` | `host_name`, `head_seq` (the latest seq on the host), `open_decisions` (ids still waiting for the human), `projects` (`[{project_id, name, state}]`) |
| `ok` | `req_id`, optional `project_id`, optional `task_id` |
| `event` | see section 5 |
| `error` | `code`, `message`, optional `req_id` |
| `file_chunk` | `req_id`, `path`, `sha256`, `offset`, `total`, `data` (#972, section 7e) |

Error codes: `bad_request`, `unauthorized`, `bad_token`, `token_expired`, `token_used`, `bad_code`, `code_expired`, `rate_limited`,
`unknown_project`, `unknown_decision`, `busy`, `agent_unavailable`, `internal`, and for `get_file` `bad_path`, `not_found`, `too_large`.
`message` is plain English that is safe to show. Error and event text never contains vendor or product names, absolute paths, or keys.

## 5. Events and resume

```json
{"type":"event","seq":42,"at":"2026-11-13T06:30:01.120Z","project_id":"p_hello-world-cli","kind":"file_written","title":"Wrote hello.js","path":"hello.js"}
```

- `seq` is a host-wide integer that starts at 1 and increases by exactly 1. Each event is **appended to `events.jsonl` before it is sent**.
- `kind` is one of:
  - `started`: a run began, with `run_id`.
  - `message`: agent text, in `detail`.
  - `file_written`: `path`, relative to the project; optional `part_id` (#972).
  - `plan`: `task_id`, `parts` (#972, section 7b).
  - `preview`: `commit`, `path`, `bytes`, `sha256` (#972, section 7d).
  - `command_run`: `command`, optional `exit_code`.
  - `question`: `decision_id` and `options: [{id,label}]`. It stays open until answered.
  - `answered`: `decision_id`, `option_id`.
  - `finished`: `ok`, `exit_code`, optional `reason`, optional `commit` (short sha), `files`.
  - `error`: `detail`.
  - `project_created`: `name`.
  - `task_state`: `task_id`, `state`, optional `reason` (`host_restarted`, `canceled`, `timeout`, `agent_failed`, `merge_conflict`, `worktree_failed`), optional `worktree`, optional `commit`.
  Every task event (`started` … `finished`) also carries `task_id`. `finished` adds `merged` (bool), `branch`, and `worktree` when the work was kept.

### 5a. Tasks
```
queued ──► running ──► done | failed | cancelled
  ▲  │        │  ▲
  │  │        ▼  │ answered
  │  │    needs_answer ──► cancelled | failed
  │  └──► cancelled
  └──── running | needs_answer   (reason host_restarted: the agent process is gone, the task goes back in line)
```
- Each task runs in its own git worktree (`.worktrees/<task_id>`, branch `task/<task_id>`) made off the project's `main` when it starts.
  On success the host commits what the agent left, merges into `main` (fast-forward when possible) and reports `commit` (main's new head).
  On failure, cancel, timeout or a merge conflict, nothing touches `main`; the worktree is kept and `worktree` says where.
- One task per project is active (`running` or `needs_answer`) at a time; the rest wait `queued`. The host caps agents in total
  (default 2) and wall-clock per run (default 30 min, then `failed` with reason `timeout`).
- A question opens with a `question` event then `task_state needs_answer`. It closes with `answered` (then `running`), or with any
  other state change of its task. `welcome.open_decisions` lists the open ones.
- A question that ends the agent's turn survives a host restart: the answer resumes the same agent session.
- **Resume**: the client remembers the highest `seq` it has *processed*. After any drop it reconnects and sends `resume` with that
  `last_seq`. The host replies `welcome` and then every event with `seq > last_seq` in order, then live events. Nothing is skipped
  and nothing is duplicated. A client should still ignore any `seq` ≤ its last one.
- **Questions survive drops**: a `question` is in the log, so replay delivers it. `welcome.open_decisions` also lists every
  question still open, so a client that lost its UI state (an app restart with an old `last_seq`) can re-request it with
  `resume` `last_seq: 0`. An open question stops being open only when an `answered` event is logged for it or its task changes state (section 5a).
- An event carries a `title` for the UI. It is short, has no vendor names and no absolute paths, and a person can read it aloud.

### Mapping to the panel schema (`agents/bridge` `AgentEvent`)
`started` → `task_assigned` + `status WORKING`; `message` → `message`; `file_written` → `tool_call` (tool `write`);
`command_run` → `tool_call` (tool `bash`); `question` → `status INPUT_REQUIRED` with `options`; `answered` → `status WORKING` with
`answer`; `finished` → `done` or `error` + `status COMPLETED|FAILED|CANCELED`. `task_state queued` → `task_assigned`;
`task_state needs_answer` → `status INPUT_REQUIRED`; `project_created` → no panel event (refresh the project list).

## 6. Versioning
The `v=1` in the QR and TXT record and the `/v1` path move together. A host that cannot speak a client's version closes with 4426.

## 7. Build from nothing: plan, preview, follow-up (#972)

The headset shows a project being built from an empty folder: a plan the graph draws before any code exists, one design
question, a running preview of each version, and follow-up changes. Code against these frames; `build_flow.json` is the
recorded sequence.

### 7a. Sequence
```
c2h create_project ──► ok {project_id, task_id}
h2c project_created, task_state queued, task_state running, started
h2c plan      {task_id, parts}                    ◄ before any file is written
h2c question  {decision_id, options: 2 or 3}      ◄ the one design question; allow_text: true
h2c task_state needs_answer
c2h answer ──► ok, answered, task_state running, started ("Agent resumed")
h2c file_written {path, part_id?} …, message …, finished {ok, merged, commit (short)}
h2c preview   {commit (40 hex), path: "index.html", bytes, sha256}
h2c task_state done
c2h get_file {commit, path, offset} … ──► file_chunk … (or error)
c2h prompt {project_id, text} ──► ok {task_id}    ◄ a follow-up: no plan, no question
h2c task_state queued … file_written … finished … preview … task_state done
```

### 7b. Plan phase (a new project's first task)
- The **first task of a project made by `create_project`** gets the plan template: the agent must end its first turn with the
  closing answer `{"status":"question", summary, question, options: 2 or 3, parts: 3 to 6}` and write no file. It builds only
  after the answer (the same agent session is resumed with it).
- The host then logs **`plan`** and, right after it, the **`question`** (`plan.seq < question.seq`).
- `plan` fields: `task_id`, `run_id`, `title` (`"Plan: <names>"`), `parts: [{id, name, files}]`.
  - `parts`: 1 to 6 (the agent is asked for 3 to 6; the host never invents parts).
  - `id`: `^[a-z0-9_-]{1,16}$`, unique within the plan (the host rewrites a bad or repeated id to `p<n>`).
  - `name`: 1 to 24 characters, cleaned like every title (no vendor names, paths or keys).
  - `files`: relative paths (`/`-separated, no `.`/`..` segments) of the files that hold the part; may be empty.
- A project has one plan. The client keeps it for the project: later `file_written` events (follow-ups too) refer to it.
- **`file_written.part_id`**: present when `path` is in some part's `files`; it names the **first** such part in plan order.
- If the agent answers without `parts`, there is no `plan` event and the question is an ordinary one. If it builds without
  asking, the task simply finishes. The client must not wait for a plan.

### 7c. Project shape (pinned by the host's template, checked by the gate, not enforced by the host)
A static web app: entry `index.html`, **at most 3 files**, no build step, no network, no CDN or other external URL; it works
offline when opened from a folder, fits a **1280×800** panel, takes **tap/click input only**, and its visible text names no
company, product or brand. `tools/host_gate/verify_program.mjs` `verifyWebApp` checks the commit independently.

### 7d. `preview` (after each commit on main)
- Logged right after `finished` (when `merged: true`) and before `task_state done`, **only if `index.html` is tracked at
  main's new head** and is at most 512 KB. A task that merged nothing, or a project without `index.html`, has no preview.
- Fields: `task_id`, `run_id`, `title: "Preview ready"`, `commit` (full 40-hex sha of main's head; it starts with
  `finished.commit`), `path: "index.html"`, `bytes`, `sha256` (lowercase hex of the file's bytes as tracked).
- Every `preview` stays valid: an older `commit` on main can still be fetched.

### 7e. `get_file` / `file_chunk`
| dir | type | fields |
|---|---|---|
| c2h | `get_file` | `req_id`, `project_id`, `commit` (7 to 40 lowercase hex), `path`, `offset` (bytes, ≥ 0) |
| h2c | `file_chunk` | `req_id`, `path` (echoed), `sha256` (whole file), `offset`, `total` (file size), `data` (base64, ≤ 6000 chars = ≤ 4500 bytes) |

- One request, one chunk. Ask again with `offset += decoded length` until `offset == total`; then check the SHA-256 of the
  concatenated bytes against `sha256` (and against the `preview` that named the commit). An empty file is one chunk with `data: ""`.
- What is served: **only a regular file tracked at that commit, where the commit is on the project's `main`**, read from the
  git object store (never the working folder). Other files of the app (`style.css`, `app.js`) are fetched the same way at the
  same commit; the preview window resolves `index.html`'s relative references with them.
- Refusals (an `error` with the `req_id`; the connection stays open):

| case | code |
|---|---|
| project not known | `unknown_project` |
| path not relative and plain (`..`, `.`, leading `/`, `\`, drive letters, empty) | `bad_path` |
| path not tracked at the commit (untracked, ignored, folder, symlink, `.git/…`), commit unknown, or commit not on main | `not_found` |
| file over 512 KB (524288 bytes) | `too_large` |
| `commit` not hex (a ref name or an option), path over 200 chars, `offset` past the end, missing field | `bad_request` |

- `file_chunk.path` echoes the request; the file's **content** is the agent's work and is sent as is (it is not cleaned like
  titles). Show it only inside the preview window.

### 7f. Follow-up
`prompt` on an existing project queues a new task in the same repository, made off `main` (so it starts from the last
preview). Its template keeps the project shape and tells the agent not to ask; there is no `plan`. On success it is merged
and a new `preview` follows with a new `commit`.
