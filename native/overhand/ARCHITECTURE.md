# Overhand native app: who owns what

**Rule: new features add a file, not lines in `OverhandActivity`.** A feature implements
`Feature` (in `features/Shell.kt`), is listed once in `Shell.features`, and from its own file
contributes lifecycle hooks, Settings rows (`settingsRows()`) and its debug-intent handler
(`onDebugIntent`, one entry per feature in `Shell.debugIntents`).

All paths below are under `app/src/main/java/com/overhand/app/`.

| File | Owns |
|---|---|
| `OverhandActivity.kt` | Android/SDK callbacks only: fans `onCreate`, `onSceneReady`, the 10 Hz frame loop, `onPause/onResume/onDestroy` and `onNewIntent` out to `Shell.features`. |
| `features/Shell.kt` | `Feature` interface, `SettingsRow`, shared state (UI handler, ECS command queue, prefs, mode generation, recorded/live sources, `paused`/`stopped`), and the feature list. |
| `features/WindowLayout.kt` | The six window specs, entities, saved poses, station-relative origin, reset layout. |
| `features/Dashboards.kt` | Live dashboard WebViews: load, freeze to still, activate, connection check. |
| `features/LadderProbe.kt` | `ladder_step` debug launches (two bare WebView panels). |
| `features/AudioFeature.kt` | Earcons and audio: tick system, pause/resume, Settings row, audio probes. |
| `features/MixedRealityFeature.kt` | Passthrough and MRUK: room mode, desk frame, Settings toggle, `mr_*` probes. |
| `features/PolishFeature.kt` | `polish_action` evidence probes. |
| `features/InteractionFeature.kt` | ISDK interaction and quit: hand-pose decision cards, architecture graph, their panels, Settings rows, quit, `graph_*`/`decision*` probes. |
| `features/OnboardingFeature.kt` | Hands onboarding: Settings replay row, onboarding probes. |
| `features/WindowsFeature.kt` | The six windows' panel registration, stations, gaze activation, layout persistence, `windows_*`/`stations_move` probes. |
| `features/SessionModeFeature.kt` | Live/Recorded mode: debug `session_mode` prep hook, mode restarts, Settings server rows, `windows_connect`. |
| `features/VoiceFeature.kt` | Voice spike (task 736): Settings row 80, push-to-talk only in debug builds made with `-PoverhandVoice` (code in `app/src/voice`). |
| `features/SettingsFeature.kt` | Settings registry (builds rows from every feature in `order`) and its own about/reset rows. |

## Ordering

`Shell.features` order is the dispatch order for every hook and for debug intents:
audio, mr, polish, interaction, onboarding, windows, session, settings, voice. Harnesses combine
`polish_action` with `windows_snapshot`/`decisions_snapshot`/`graph_metrics` in one intent, so
polish must stay before interaction and windows.

Settings rows are built in ascending `order`: 10 audio, 20 onboarding, 30 about text,
40 session/server, 50 about and reset layout, 60 passthrough, 70 decisions and quit,
80 voice (debug spike), 90 first connection check. Pick an unused number to place a new row.
