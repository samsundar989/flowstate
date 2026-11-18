# Security of the host link

> Written by t906 (2026-11-13) against the host on `main` at `60655983` (`host/`) and the headset client in
> `native/overhand/app/src/main/java/com/overhand/app/hostlink/`. Every claim names its source: a file, a test, or a task
> report (`af task show <id>`). **Worn status:** nothing below has been exercised by a person wearing the headset. The device
> runs (#814, #815, #862) used a Quest 2 lying asleep on a desk, driven by injected pinches and audio under
> `scripts/device_lease.mjs`. developer's worn host gate (`docs/testing/host_gate_runsheet.md`) has not happened yet.

## What the host is, in security terms

A program on your computer (`host/`) that lets a paired headset **start your own coding agent on your computer**. Anyone who
can pair with it can make the agent write files and, with your approval on the headset, run commands. Everything below exists
to make sure only your headset can pair, and that a stranger on the same Wi-Fi cannot read or inject traffic.

## Threats it is built for

- A stranger on the same Wi-Fi who connects to the port, guesses codes, or replays frames.
- A relay in the middle (ARP spoofing, a fake mDNS answer) that presents its own certificate.
- A headset that loses Wi-Fi mid-task (no lost or duplicated events, no duplicate project).

It is **not** built for: an attacker who already runs code as you on the computer (they can read the config folder), a stolen
unlocked headset that is already paired, or use across the internet (it binds to the LAN; see Known limits).

## Security claims and their sources

| # | Claim | Source |
|---|---|---|
| S1 | The host listens on **one TLS port** (default 47821) on **one LAN address**, a private IPv4 on a non-virtual adapter, Wi-Fi first. It does not bind `0.0.0.0` unless you pass `--bind`. | `host/src/main.ts` `lanAddress()`, `host.start()`; `host/src/protocol.ts` `DEFAULT_PORT`; test `host picks a LAN address (non-loopback) when one exists` (`host/test/host.test.ts`) |
| S2 | TLS 1.2 or newer, with a self-signed ECDSA P-256 certificate created on first run and kept in your config folder. There is no plaintext endpoint. | `host/src/server.ts` `createServer({ ..., minVersion: "TLSv1.2" })`; `host/src/config.ts` `loadIdentity()` |
| S3 | The headset **pins** the certificate by the SHA-256 of its DER bytes. A different certificate is refused before any frame is sent; the hostname check re-checks the pin, since the certificate names no IP. The host link only ever opens `wss://` (but see Known limits 13 on the app's cleartext setting). | Headset: `hostlink/HostLink.kt` `PinningTrustManager`, `hostnameVerifier`; reference client: `host/src/client.ts` `pinnedSocket()`; test `wrong fingerprint refused by the client before any frame is sent` (`host.test.ts`); on device, a stale pin gave "This is not the computer you paired with. Nothing was sent." (#814 report) |
| S4 | **Discovery** is mDNS/DNS-SD `_flowstate._tcp`, with TXT `v`, `fp` (certificate fingerprint), `n` (computer name), `pair=confirm1`. The fingerprint learned this way is **not trusted on its own**: it is only kept after the six-digit confirm (S5) succeeds. | `host/src/main.ts` `mdns.publish`; `hostlink/HostDiscovery.kt`; `HostLink.kt` `pairByConfirm` ("the fingerprint is stored only now, after `paired`"); `host/scripts/mdns_check.mjs` (#783: discovered, fp matches) |
| S5 | **Pairing by six digits confirmed on the computer** (default on the headset): the headset sends a fresh device public key; the host commits to its nonce `c = H(fp, device_pub, Na)`; the headset sends its nonce `Nb`; only then does the host reveal `Na`. Both sides show `digits = H(fp, device_pub, Na, Nb) mod 10^6`, where `fp` is the certificate each side actually used. The person at the computer types `y` only if the digits match. Nothing is issued before the `y`. | `host/src/pairing_confirm.ts` (header comment, `PairConfirm.frame`, `decide`); `HostLink.kt` `pairByConfirm`; `docs/host/PROTOCOL.md` §2d; tests `happy path: same six digits on both screens; nothing issued before the y; the issued secret resumes`, `the computer says no: refused, closed, nothing stored` (`host/test/confirm.test.ts`) |
| S6 | A relay with its own certificate cannot make the two screens agree: a transparent relay breaks the commitment on the headset, an active relay yields different digits, and a relay that tries to force the host's digits breaks its own commitment. Chance of a match by trying keys or nonces is 10^-6 per attempt. | `pairing_confirm.ts` header; tests `MITM, transparent relay …`, `MITM, active relay …`, `MITM, grinding relay …` (`confirm.test.ts`, relay in `host/test/mitm_relay.ts`); headset side `runs/host/pair_confirm/jvm_mitm_*.json` |
| S7 | Confirm attempts are limited: one pending request at a time, 120 s each, then refused as expired; 3 refusals per source address per minute, then `rate_limited` without asking the person. A client that withholds its nonce never learns the host's. | `pairing_confirm.ts` `CONFIRM_TTL_MS`, `MAX_REFUSALS_PER_MIN`; tests `timeout: an unanswered request expires after 120 s and a late y issues nothing`, `one pending request at a time; 3 refusals a minute per source …`, `a client that withholds its nonce …` (`confirm.test.ts`) |
| S8 | **Fallback: typed six-digit code** (keypad on the headset) or a QR token (other clients; the headset app has no QR reader). Code and token live in host memory only, expire after 120 s, are single use, compared in constant time; 5 wrong codes burn the code; 10 failures a minute lock the source for a minute. After pairing both screens show a 4-character check derived from the fingerprint and the secret. | `host/src/pairing.ts`; `protocol.ts` `TOKEN_TTL_MS`, `CODE_TTL_MS`, `MAX_CODE_TRIES`, `MAX_FAILS_PER_MIN`, `checkCode`; tests `wrong code refused, socket closed, no device stored`, `right code pairs and both sides compute the same check`, `expired token refused`, `reused token refused`, `guessing is rate limited and a code is burned after 5 wrong tries` (`host.test.ts`), `typed code still pairs on a host that also offers confirm-on-computer` (`confirm.test.ts`) |
| S9 | Pairing issues a random 32-byte device secret. The host stores only its **SHA-256**; every later connection must `resume` with the device id and secret, or it is refused. Unpaired connections may send only pairing frames; one that sends no pairing request within 10 s is closed. | `pairing_confirm.ts` `decide`; `server.ts` `onConnection` (`resume` branch, `AUTH_TIMEOUT_MS`); test `frames other than pairing are refused before auth; bad device secret refused` (`host.test.ts`) |
| S10 | On the headset, the paired record (address, port, fingerprint, device id, secret) is AES-GCM encrypted with a key that never leaves the Android Keystore. "Forget this computer" deletes it. | `hostlink/PairedHostStore.kt` `KeystorePairedHostStore`; `HostLink.forget()`; #814 report ("forgotten" state seen on the device) |
| S11 | Every frame is validated against the wire contract in both directions (unknown fields and types rejected); messages are capped at 64 KiB. | `protocol.ts` `validate`; `server.ts` `maxPayload: 64 * 1024`; tests `every protocol fixture frame validates in its direction`, `validator rejects malformed frames` (`host.test.ts`) |
| S12 | The host **reads, stores and forwards no credentials** for the agent. It starts the coding CLI you installed and signed in to yourself; that CLI uses its own login (a subscription sign-in or the API key you configured in it). Nothing about the key passes through the host or the headset. | `host/src/agents.ts` header and `startRun` (spawns `claude`/`codex` with no credential arguments or environment changes); `host/PROVENANCE.md` "Not shipped"; `docs/host/DESIGN.md` §2 |
| S13 | Text sent to the headset is cleaned: absolute paths become `[path]`, key-shaped strings (`sk-…`, `gh?_…`, `AKIA…`) become `[redacted]`, vendor names become "assistant", and length is capped. This is a best-effort filter, not a guarantee. | `agents.ts` `clean()` |
| S14 | The agent works in its own git worktree (`<project>/.worktrees/<task>`). Agent B runs with its `workspace-write` sandbox (writes outside the worktree refused). Agent A runs with `--permission-mode acceptEdits`: file edits are allowed, anything else your own CLI settings do not already allow becomes a Yes/No question on the headset. The prompt goes to agent B on stdin, never on a shell command line. | `agents.ts` `startRun` (spawn arguments and comments); `host/src/git.ts` `ensureWorktree`; #783 report (live runs: 2 permission questions answered over the link) |
| S15 | Answers from the headset reach the computer only while **Send real answers** is on in Settings; with it off nothing is sent. | `docs/testing/host_gate_runsheet.md` step 3; `docs/host/CLIENT.md` "Wiring"; #814 report ("the Live answers switch still gates sends") |
| S16 | Cancel or timeout kills the agent's whole process tree (`taskkill /T /F` on Windows); main is left untouched and the worktree kept. After a crash the host kills only an orphan whose process is still a node or agent process. | `agents.ts` `killPidTree`, `isAgentPid`; `server.ts` `recover`; tests `cancel kills the agent process tree and reports a canceled finish` (`host.test.ts`), `cancel while the agent writes: …`, `host process killed mid-task: …` (`core.test.ts`) |
| S17 | A dropped Wi-Fi link loses no events: every event is on disk before any client sees it, and a client resumes from its last sequence number. | `host/src/eventlog.ts`; tests `fake headset: resume after a mid-run drop loses no event …` (`host.test.ts`); on device, `link_lost_events=0` over 4 drops (#814, `runs/host/connect_device/result.json`) and a 10.2 s drop mid-run lost 0 (#815, `runs/host/voice_project/device.json`) |

## What is stored, and where

On the computer, in the config folder (`%APPDATA%\flowstate-host`, else `$XDG_CONFIG_HOME/flowstate-host` or
`~/.config/flowstate-host`; `FLOWSTATE_HOST_HOME` or `--config` override it). Source: `host/src/config.ts`, `host/src/server.ts`.

| File | Contents | Sensitive? |
|---|---|---|
| `host.key`, `host.crt` | the TLS private key and certificate | **yes**: the key is the host's identity. Mode 0600 on Linux/macOS; on Windows only the user profile's ACL protects it (`config.ts`, the `chmod` is a no-op there) |
| `devices.json` | per paired device: id, SHA-256 of its secret, name, pairing time, source IP | hashes only |
| `cli.secret` | the secret of the local command line, which is itself a paired device (`d_local`) | **yes**: it lets a local program act as a paired device |
| `events.jsonl` | every event the headset saw (cleaned text: titles, file names, commands, questions, answers, commits) | your project activity |
| `raw/<run>.<agent>.jsonl` | the agent's **raw, uncleaned** output stream, including stderr | **yes**: whatever the agent printed, unfiltered |
| `tasks/<task>.json` | the prompt, the agent's session id (to resume), process id, open question | your prompts |
| `host.json` | pid, address, port, fingerprint while running | no |
| `final_schema.json` | the closing-answer schema given to the agent | no |

Your projects are in `~/FlowstateProjects/<name>` (`--projects` changes it), one git repository each (`host/src/git.ts`).
On the headset: only the encrypted paired record (S10). Nothing is written to this repository.

## Known limits (honest list)

1. **Not verified worn.** Pairing, the link and voice requests ran on a Quest 2 asleep on a desk with injected input (#814,
   #815, #862). The agent's question card has **never run on the device** (`host_question_answered_device=0`, #862 report).
2. **Windows 11 only.** Host, tests and live runs were run on one Windows PC (#783 `host_platforms_tested=1`). macOS and Linux
   share the code but were not run.
3. **Any paired device can start agent work** on your computer, and agent A's file edits are pre-approved. Pair only your own
   headset; treat the device secret like a password.
4. **No revoke command.** To remove a paired device, stop the host and delete its entry from `devices.json` (the host says so
   when it pairs one: `server.ts`). Planned: `docs/host/DESIGN.md` §3 item 9.
5. **The typed-code fallback is weaker than the confirm.** Its six digits travel inside a TLS session whose pin came from
   unauthenticated mDNS; only the 4-character check, if the wearer compares it, reveals a relay. A PAKE is planned
   (DESIGN.md §3 item 9).
6. **The host key is a file, not in the OS keychain** (DESIGN.md §2 and §3 item 9). Anyone who can read your user profile can
   impersonate the host and read `cli.secret` and `raw/`.
7. **mDNS advertises the computer's name and fingerprint** to everyone on the LAN while the host runs (`--no-mdns` turns it off;
   then pair with the typed code).
8. **Logs grow without limit** and `raw/` is unfiltered (no rotation yet: DESIGN.md §3 item 9).
9. **The agent runs your own CLI with your own settings**, hooks and plugins included (#783 report, Surprises). Its sandbox and
   permission rules are the CLI's, not ours.
10. **No bring-your-own-API-key page.** The host relies on the CLI's own login. The planned local key page (DESIGN.md §3 item 7)
    is not built. Whether driving a subscription-signed CLI from a program you give to others is allowed is an open question
    (DESIGN.md §6; #783 report, "CLI terms verdict").
11. **LAN only.** There is no relay or internet path. Away from home, a private tunnel such as Tailscale is a documented option,
    not tested here (DESIGN.md §5).
12. The guest-network and firewall failure texts on the headset are covered by unit tests only; they were not triggered on the
    device (#814 report).
13. **The headset app still permits cleartext HTTP to any host** (`native/overhand/app/src/main/res/xml/network_security_config.xml`,
    `cleartextTrafficPermitted="true"`, kept for the older dashboard Live mode over `adb reverse`). The host link does not use
    it, but the app-wide setting should be narrowed to `127.0.0.1` before a public release.
14. The host terminal still says "Scan the code with the headset", but the headset app has no QR reader
    (`HostLink.pairWithQr` has no caller in `native/overhand/app/src/main`); on the headset, pair by confirm or the keypad.
