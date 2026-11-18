# Flowstate host

One command on your computer and no window. It pairs with your headset over Wi-Fi and runs your own installed, signed-in
coding CLI (`codex` or `claude`, whichever you have; with both, `auto` starts with `codex`, and `--agent claude` picks the other) on your projects. Each request is a task in a
queue, done in its own git worktree, merged into the project's `main` when it succeeds. Questions come to the headset; your
answer resumes the same agent session.

## Install and first run
1. Install Node.js 22.6 or newer, and git. Install one coding CLI and sign in to it once in a terminal (the host never sees that sign-in).
2. clone this repository then `cd host && npm ci` (and `npm test`: offline, the current test suite).
3. `npm start -- --max-running 1` (Windows PowerShell: `npm.cmd start -- --max-running 1`). The terminal shows the address, a keypad
   fallback code, the certificate fingerprint, `Coding agent ready: …` and "Discoverable on the local network". Leave it running.
4. Windows only: allow Node.js in the firewall prompt, ticking the network type your Wi-Fi uses (Private or Public).
5. In the headset: Settings, **Connect your computer**. It finds this computer by itself and shows six digits. The six digits to compare are NOT the code printed at start: when the terminal
   prints the same six (`Pair "Headset" (…)? Code 123 456. Type y to allow`), type `y`. (Fallback: the headset's keypad takes the
   6-digit code the terminal prints. The QR code is for other clients; the headset app does not read it.) Then turn
   **Send real answers** on in Settings.
6. Say "new project, a command-line tool that prints the time". Without a headset, in a second terminal:
   `node bin/flowstate-host.mjs new "clock" --prompt "a hello-world CLI that prints the time" --follow`
7. In a second terminal in `host/`: `node bin/flowstate-host.mjs status` lists projects, tasks and open questions; `answer <question id> <choice>` answers one
   (questions and results appear in the headset; the host terminal itself shows a `task … in <folder>` line per run, and `host watchdog nudge` if the agent is quiet for two minutes, which is normal).
8. Your projects are in `~/FlowstateProjects/<name>` (`--projects` changes it). `<name>` is the first words of the request (for example
   `tiny-tap-counter-with-big`); the terminal's `task … in <folder>` line shows the folder and `status` shows it as `p_<name>`. `git log` there shows the agent's commits.
9. `node bin/flowstate-host.mjs stop` (or Ctrl+C in its terminal) stops the host; the `npm start` terminal may then report exit code 1, which is expected; running tasks go back in line and resume on the next start. `stop t3` cancels task t3.
10. Tested on **Windows 11 only** (host, tests, live runs). macOS and Linux use the
    same commands but were **not run**. The headset steps have not yet been done by a person wearing it.

Options for `start`: `--bind <LAN address>` · `--port 47821` · `--projects <folder>` · `--agent auto|codex|claude` · `--config <folder>` ·
`--per-project 1` (agents per project) · `--max-running 2` (agents in total) · `--task-timeout-min 30` · `--no-mdns`.

If you missed the firewall prompt (admin PowerShell):
`New-NetFirewallRule -DisplayName "Flowstate host" -Direction Inbound -Program "C:\Program Files\nodejs\node.exe" -Action Allow -Profile Private,Public`.
The headset and the PC must be on the same Wi-Fi with no "client isolation" (some guest networks have it).

## What it does with your projects
- A task runs in `<project>/.worktrees/<task>` on branch `task/<task>`, made off `main` when it starts. One agent per project at a time.
- Success: the host commits what the agent left and merges into `main` (fast-forward when it can). Failure, cancel, timeout or a merge
  conflict: `main` is untouched and the worktree is kept; `status` says where.
- The agent works inside its worktree: agent B's sandbox refuses writes outside it; agent A may edit files in its folder without asking and asks you (on
  the headset) before anything else your own CLI settings do not already allow. The host never reads or stores your sign-in.
- State lives in your config folder (`%APPDATA%\flowstate-host`, else `~/.config/flowstate-host`): `events.jsonl` (everything the
  headset saw; the host rebuilds from it after a crash), `tasks/` (session ids to resume), the host certificate, paired devices (hashed secrets), `raw/` (the agent's unfiltered output).

Security (claims, sources, known limits): `../docs/open_source/SECURITY.md`. Protocol: `../docs/host/PROTOCOL.md`. Design: `../docs/host/DESIGN.md`. Tests: `npm test` (offline). Provenance: `PROVENANCE.md`.
