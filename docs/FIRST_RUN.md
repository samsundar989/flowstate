# First run

Two ways in. The first needs nothing but the headset. The second connects your own coding agent on your own computer.

## Try it (no computer): about 1 minute

1. Put on the headset and open Flowstate.
2. It starts on a **recorded day**: a real day of agent work, replayed. The board says `Recorded day · <time> · replay`.
   Nothing on first launch asks for a computer, an account or a key.
3. Answer the cards, open the windows and talk to it. In recorded mode nothing is sent anywhere.

If you open **Settings > Link > Connect your computer** and close it again, you are back on the recorded day with
nothing changed.

## Use it with your own agents: about 3 minutes of machine time, plus the one-time app build

You need a PC on the same Wi-Fi as the headset, Node.js 22.6+, and one coding CLI (`codex` or `claude`) **installed and
signed in** on that PC. The host uses that CLI's own account; there is no Flowstate account, and no key goes in the app.
The CLI's provider may charge for the work.

| Step | What you do | Measured on a PC (Nov 18) |
|---|---|---|
| 1 | `git clone https://github.com/samsundar989/flowstate.git flowstate` | 2 s |
| 2 | `cd flowstate/host` then `npm ci` (Windows PowerShell: `npm.cmd ci`) | 4 s |
| 3 | Check your agent: `codex login status` or `claude auth status` must say you are signed in | 1–3 s |
| 4 | `npm start -- --max-running 1` (leave it running). It says `Coding agent ready: …` (the code it prints at start is only the keypad fallback) | under 1 s |
| 5 | Headset: **Settings > Link > Connect your computer**, choose your PC; the PC prints `Pair "…"? Code 123 456. Type y to allow`: check those six digits match the headset, type `y` | under 1 s plus your check |
| 6 | Headset: turn on **Send real answers** in Settings (it starts off on every launch) | — |
| 7 | Say "New project, a tiny tap counter…" and pinch **Start** | agent starts in 1–3 s |
| 8 | Answer its question on the card; wait for **Saved as commit …** | first commit 68 s (claude) to about 4 min (codex) after step 4; up to 2 min with no new card is normal |

Optional: `npm test` in `host/` checks the host first (42 tests, about 1 minute).

The app build itself (`INSTALL.md`, Android Studio and Gradle) is one-time and takes several minutes on first run.

Your project is in `~/FlowstateProjects/<name>` on the PC (or your `--projects` folder; the host's `task … in <folder>` line names it):
`git log` there shows the agent's commit. Live mode starts new
projects only; it cannot yet open an existing repository of yours (pull the project into your repository instead:
`git pull --no-edit --allow-unrelated-histories ~/FlowstateProjects/<name> main` inside it).

## When something goes wrong

Every failure ends in one sentence that says what to do.

| What happened | On the PC | In the headset |
|---|---|---|
| No coding CLI installed | `No coding agent found. Install codex or claude, sign in, then start the host again.` | `Not started: no coding agent is ready on the computer. Install one and sign in there, then send it again.` |
| CLI not signed in | `Your coding agent is installed but not signed in. Run "codex login" (or "claude") in a terminal, sign in, then start the host again.` | `Stopped: your coding agent is not signed in. Sign in on the computer, then send it again.` |
| Host not running | `node bin/flowstate-host.mjs status` says `host: stopped`; other commands say `The host is not running. Start it with npm start in the host folder, then try again.` | `The host is not running on the computer. Start it there.` |
| Wrong six digits | `pairing refused … bad_code` | `Wrong code. Check the six digits on the computer.` |
| Firewall blocks the port | The host prints at start: `If the headset does not find this computer: put both on the same Wi-Fi (not a guest network) and allow Node.js through the firewall for the network type your Wi-Fi uses (Private or Public; Windows asks at the first start).` | `The computer does not answer. Allow the host through the computer's firewall.` |
| PC and headset on different networks | same start-up line | `The computer is on a different network. Connect both to the same Wi-Fi.` |
| Host stopped mid-task | Start it again with `npm start`; the task comes back: `Back in line after a restart`, then `Picking up after a restart` | `Reconnecting…` with `The host is not running on the computer. Start it there.`; it continues on its own when the host is back |
| Send real answers is off | — | `Sending is off. Turn on Send real answers in Settings, then pinch Start.` / `Practice: nothing was sent. Turn on Send real answers in Settings to send.` |

No computer found at all? The Connect window lists the three steps and where to get the host:
`github.com/samsundar989/flowstate`.
