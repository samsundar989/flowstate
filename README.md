# Flowstate

Flowstate is a headset app for watching and steering coding agents: see their work as windows and a building site, read code, and answer decision cards.

**Recorded mode works without a PC or network after installation.** It includes a recorded day of agent work. **Live mode is a preview:** run `host/` on your PC on the same Wi-Fi, pair with six digits, then use your own installed, signed-in coding CLI.

## Try it

1. Follow [INSTALL.md](INSTALL.md) to build and sideload the headset app (Developer Mode required; no prebuilt APK is included).
2. Open the app for the recorded day. Optional voice requires fetching the speech bundle before building.
3. For Live mode, run `npm ci` and `npm start -- --max-running 1` inside `host/` (Windows PowerShell: `npm.cmd`). In headset Settings choose **Connect your computer**, compare the six digits, and type `y` in the PC terminal. Turn **Send real answers** on.
4. Say "New project, a tiny tap counter…" and pinch **Start**. The headset shows the project window with the agent's progress, a question card to answer, and **Saved as commit …** when the agent is done (about 1 to 4 minutes; the agent may go quiet for two minutes while it checks its work). Step-by-step with timings and every error message: [docs/FIRST_RUN.md](docs/FIRST_RUN.md).

**Before you start Live mode:** a PC on the same Wi-Fi as the headset (not a guest network), Git, Node.js 22.6 or newer, and one supported coding CLI installed **and signed in** on that PC (the two supported commands are named in [docs/FIRST_RUN.md](docs/FIRST_RUN.md)). The host uses that CLI's own account; there is no Flowstate account or key.

**What Live mode works on today: new projects only.** Each "New project, …" request makes a fresh git repository in `~/FlowstateProjects/<name>` (`--projects` moves it); the agent works there, the host commits and merges into that repository's `main`, and later requests change the same project. The host cannot yet be pointed at an existing repository of yours. To bring that work into a repository of your own, run `git pull --no-edit --allow-unrelated-histories ~/FlowstateProjects/<name> main` inside it (the histories differ, so git needs that flag; use your `--projects` folder if you moved it; files with the same name in both, such as `README.md`, need a normal merge-conflict fix).

See [architecture](docs/open_source/ARCHITECTURE.md), [security](docs/open_source/SECURITY.md), and [privacy](PRIVACY.md). Host commands and storage locations: [host/README.md](host/README.md).

## Source and tests

- `native/overhand/`: Kotlin headset app using the Meta Spatial SDK.
- `host/`: standalone Node.js pairing host and coding CLI runner; `cd host`, `npm ci`, `npm test`.
- `web/agentdeck/`: optional browser version; `npm ci`, `npm run build` in that directory.
- `agents/`, `assets/`, `tools/`: event schemas, recorded examples, required build assets, build-time checks.

This is a fresh source export: one initial commit plus setup fixes. Private device recordings, biometric hand-frame fixtures, workstation diagnostics, project history, deployment credentials and tests that require those fixtures are intentionally omitted. The remaining native tests are not the full private development suite. Privacy gate dictionaries are supplied locally through `APK_PRIVACY_NAMES`; no private dictionary is shipped. Layout lint's workstation baseline is omitted; generate your own before making a signed release. No APKs or captured media/data are distributed beyond the app's required bundled examples and assets.

Windows 11 and Quest 2 were used during development; Linux/macOS builds and worn-headset Live pairing have not been validated for this export. Host tests exercise pairing locally, not on a physical headset. The legacy `agents/bridge` Live server has no authentication: do not use it for Live mode; use `host/`.

## License and SDK scope

Apache-2.0 covers this project's code, subject to the exceptions in [NOTICE](NOTICE) and the asset license ledgers. Third-party assets retain their own licenses. The Meta Spatial SDK is downloaded during the build, is excluded from this license, and remains under Meta's SDK terms. Flowstate is independent and is not made, endorsed or reviewed by Meta. No vendor logos are used in this README.
