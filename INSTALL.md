# Install Flowstate

Short version with timings: [docs/FIRST_RUN.md](docs/FIRST_RUN.md). These steps build from source; there is no prebuilt APK. Recorded mode needs no PC once installed. Live mode needs the host on the same Wi-Fi.

## Prerequisites

Install Git, Node.js 22.6 or newer (22.12+ for the optional web app), Android Studio, and a JDK 17+ (Android Studio's bundled JBR works). In Android Studio's SDK Manager install Android SDK Platform 34, Build-Tools 34, and Platform-Tools. Enable Developer Mode on your Quest, connect it over USB, and accept USB debugging on the headset. Install the Meta Quest developer driver on Windows if your headset is not detected. This task's export has not been installed on a headset.

On this public repository page, choose **Code > HTTPS** and copy its clone URL. In the command below replace `PASTE_CLONE_URL` with that exact URL (no angle brackets):

```sh
git clone PASTE_CLONE_URL flowstate
cd flowstate
```

## Windows PowerShell

Run these in PowerShell, not Command Prompt. Adjust paths if Android Studio or the SDK is elsewhere. On Windows use `npm.cmd` in place of `npm` in every command below; this avoids PowerShell blocking Node.js's npm.ps1 shim when script execution is disabled (no machine-wide execution-policy change is needed).

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$env:PATH = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:PATH"
java -version
adb devices
```

Your headset must appear as `device`, not `unauthorized`. For optional offline voice, install **Git for Windows** and run the following in **Git Bash** from the repository root (Git Bash supplies bash, curl, tar and sha256sum; Windows PowerShell does not):

```sh
bash tools/voice_judges/fetch_voice_model.sh
```

Back in PowerShell at the repository root:

```powershell
cd native/overhand
.\gradlew.bat assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
cd ../..
```

## macOS / Linux shell

Use a shell such as bash. These platform builds have not been tested. Set actual installation paths:

```sh
export JAVA_HOME="/path/to/jdk-17-or-newer"
export ANDROID_HOME="$HOME/Android/Sdk"  # macOS usually $HOME/Library/Android/sdk
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
java -version
adb devices
# Optional voice (bash, curl, tar, sha256sum required):
bash tools/voice_judges/fetch_voice_model.sh
cd native/overhand
chmod +x gradlew
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
cd ../..
```

The first build downloads Gradle, Android dependencies and the Meta Spatial SDK. Launch Flowstate from the headset's Unknown Sources list. It starts with a bundled recorded day. Without the optional voice bundle it still builds and runs; voice is off. `-PoverhandVoice=off` explicitly disables voice. Android uses the package ID `com.overhand.app`.

Optional JVM unit tests: from `native/overhand`, run `.\gradlew.bat testDebugUnitTest` (PowerShell) or `./gradlew testDebugUnitTest` (bash). Private-fixture tests have been omitted; see README.

## Run and pair the host (Live preview)

First make sure your coding CLI is installed **and signed in** on this PC (see the paragraph below on `codex` and `claude`). One of these must report that you are signed in:

```sh
codex login status
claude auth status
```

Then, from the repository root in a new terminal (Windows PowerShell: `npm.cmd` in place of `npm`):

```sh
cd host
npm ci
npm test
npm start -- --max-running 1
```

The terminal prints the host's Wi-Fi address, a six-digit pairing code and certificate fingerprint, then `Coding agent ready: ...`. If it says instead that no coding agent was found or that it is not signed in, do what that line says and start the host again; until then the headset's project window shows the same advice. The PC and headset must share Wi-Fi; guest networks with client isolation can block discovery. Allow Node.js through the Windows firewall on your trusted private network. If needed, run this **single line** in administrator PowerShell:

```powershell
New-NetFirewallRule -DisplayName "Flowstate host" -Direction Inbound -Program (Get-Command node.exe).Source -Action Allow -Profile Private
```

On the headset open **Settings > Connect your computer**, select your PC, compare the six digits shown on the headset and terminal, then type `y` in the terminal only if they match. If the keypad fallback is offered, enter the six digits printed by the host. QR codes are for other clients; the headset does not scan them. Enable **Send real answers** in Settings. If discovery fails, check firewall and Wi-Fi isolation; `--bind <your PC's Wi-Fi IPv4 address>` selects a specific interface.

To run live coding, install and sign in to your own supported CLI (`codex` or `claude`) before starting the host. The host uses that CLI's existing account; its provider may charge for work. There is no hosted Flowstate account. Without a CLI, you can try the host's recorded replay in a terminal:

```sh
node bin/flowstate-host.mjs --agent replay --replay test/fixtures/streams/codex_build.jsonl
```

If a task stops with "not signed in", sign in to the CLI in a terminal and send the request again from the headset; you do not need to restart the host for that. If the host stops mid-task, start it again with `npm start`: the task goes back in line and picks up by itself, and the headset reconnects on its own.

Stop the first host before starting another. In a second terminal in `host/`:

```sh
node bin/flowstate-host.mjs status
node bin/flowstate-host.mjs stop
```

Projects default to `~/FlowstateProjects/`; host state is in `%APPDATA%/flowstate-host` on Windows or `~/.config/flowstate-host` elsewhere. See host/README.md for creation commands, options, recovery and how to remove local state. Host tests do not prove physical-headset pairing.

## Optional browser app

From the repository root, enter `web/agentdeck` and run `npm ci`, then `npm run build`. Static output is `dist/`. `npm run dev:runtime` starts a local development server; stop it with Ctrl+C.

Signed releases require your own keystore and `RELEASE_KEYSTORE`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`. Never commit keys. Signed release tasks enforce voice, privacy, brand and layout checks; this source export's documented install path uses a debug build.
