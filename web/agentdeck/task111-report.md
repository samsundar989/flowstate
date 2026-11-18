# Task 111

**Outcome:** Partial: eight portfolio stations and the hand decision inbox are implemented; **27/27 replay runs pass**, but the literal production brand scan remains **96 hits**, identical to the starting build.

**Numbers** (paths below use `runs/sim/web_stations_`):
- `npm run typecheck`: exit 0 (`typecheck.log`). `npm test`: 17/17 (`tests.log`).
- `node --experimental-strip-types --no-warnings scripts/replay-smoke.mjs`: 9 fixtures × auto/poke/gesture, 0 failures (`smoke.log`); starting revision b74f5e3: 5 failures (`baseline_smoke.log`). Both portfolio fixtures produce 8 stations; sample 1 decision, recorded day 15. The legacy 21 runs remain green. Gesture mode snoozes each portfolio decision once, then accepts its recommendation, with an explicit poke fallback for malformed decision 9.
- `npm run build`: 4,660,508 bytes versus baseline 4,528,391; both below 5 MB (`build.log`, `baseline_build.log`). Baseline was built from a temporary `git archive b74f5e3` with the same installed dependencies.
- `node scripts/stations-emulator.mjs answer/promote/poke`: synthetic thumb emits `input_response ... decision_id=12 answer=A`; actual poke chooses B; pinch promotes and poke demotes. Current-load console errors: 0 (`console.json`, `poke_console.json`, `interaction_console.json`). Five required PNGs exist: `arc`, `promoted`, `decision`, `answered`, `inbox_zero`.
- `node scripts/stations-emulator.mjs profile` / `profile-legacy`: uncalibrated host-browser rAF p50/p95 **6.1/6.2 ms vs 6.1/6.2 ms**, regression-only, not headset fps (`profile.json`, `profile-legacy.json`).
- `node --experimental-strip-types scripts/brand_grep.mjs web/agentdeck/dist`: **96 vs baseline 96**, exit 1 (`brand.log`, `baseline_brand.log`); app source/public/fixtures: **0** (`source_brand.log`). Raw bundle hits include SDK strings and shader identifiers. No scanner exceptions or bundle rewriting were introduced. Consolidated numbers: `acceptance.json`.

**Changes:** Implementation `8322801`; follow-up commit titled `[t111] Verify station target geometry and record acceptance limits`. Rebased onto main; post-rebase typecheck, 17 tests and 27 replays pass. Only `web/agentdeck/**`, `runs/sim/web_stations_*`, and `docs/toolchain_web.md` changed. New pure portfolio state, dynamic manifest stations/cards/options, recommendation matching, fresh-pose guard, project-filtered graph, spatial cues/inbox-zero payoff, 12 mm tray gaps, larger legacy step text, Exit/Recentre, tests and emulator script. No spend, submissions or pushes.

**Geometry:** 1.10 m radius; 24° spacing, 168° across 8 stations; 320 mm panels leave ~137 mm chord spacing minus width. Promotion is 0.44 m eye distance. Station activation is 60 mm (≥3°); option buttons are 50 mm with ≥12 mm gaps. No centres lie in the dead band.

**Surprises / rulings:** Ruling: show missing gates, deadlines and evidence explicitly; never invent them. Recorded fixtures have no graph step events, so promoted lanes have no execution nodes. One recommendation has no matching option; require poke. Source IDs span projects; station rows retain separate state. #110's visual system was absent from the starting revision. Remote body rows remain below 1.2°; promoted copy is larger. The emulator mirror still clips near-card left edges despite central measured camera projection/card placement: these stills are not a headset legibility pass. The raw dist gate is unmet, so this is partial.

**Next:**
- Integrate #110 and verify near-card framing/legibility on a headset.
- Correct decision 9 upstream and supply structured gate/deadline metadata.
- Have the scanner owner distinguish displayed copy from SDK/shader internals; retain the raw failing result until then.
