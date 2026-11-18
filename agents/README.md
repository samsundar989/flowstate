# agents/: the event stream for the headset

`schema/` is the event format (see its README), `fixtures/` holds recorded 3-agent runs, and `bridge/` holds the WebSocket server (Node 22, deps: `ws`, `ajv`).

```sh
cd agents/bridge && npm install
npm test                                                      # validate every fixture + replay each at 8x to a WS client (~1 min)
npm run replay -- fixtures/feature_ratelimit.jsonl --speed 8  # ws://<pc>:8765; INPUT_REQUIRED waits 45 s for the headset, then auto-answers (--hold = wait forever, --loop)
npm run client -- --assign builder="Update the changelog"     # stand-in headset: prints events, answers prompts, assigns a task
npm run live -- --cwd ../../some-repo --model sonnet --allow-edits  # headset task_assigned spawns `claude -p ... --output-format stream-json`
#   hooks: point a Claude Code HTTP hook at http://<pc>:8766/hook?agent=builder, or pipe: claude -p ... --output-format stream-json --verbose | npm run live -- --stdin --agent builder
npm run record -- --cwd <toy repo> --out ../fixtures/new.jsonl --agent "builder=..." --agent "reviewer=..." -- --permission-mode acceptEdits
npm run gen-fixtures && npm run stats                         # regenerate the synthetic fixtures (deterministic) / print DAG stats
```

Fixtures: `feature_ratelimit`, `bugfix_flaky_test` and `research_vector_db` are synthesised, 4.5 to 7 min long, each with fan-out/fan-in, one fail-and-retry and one INPUT_REQUIRED. `real_claude_run` is 3 real concurrent `claude -p` (haiku) agents on a toy Python repo, 34 s long, with paths and usernames redacted.

Shot fixtures (synthesised, seeded, labelled "Scripted scenario: ..." in the first event's `detail`), one per video beat in `docs/video_script.md`:

| fixture | seed | shot | beat |
|---|---|---|---|
| `shot_reviewer_needs_input` | 52 | S1, S7, S8 | 100 s; Reviewer asks "Apply the database migration to staging?" (thumbs-up/down) at 19 s, two tool calls after the answer, COMPLETED; Researcher and Builder keep working |
| `shot_builder_csv_export` | 53 | S4, S5, S9 | 110 s; Builder "Export the report as CSV", 9 steps with fan-out/fan-in, no failures, ends on one artifact `exports/q3_report.csv` |
| `shot_wrong_branch_redirect` | 54 | S10 | 87 s; Builder starts on `feature/x`, asks "Continue on feature/x?" at 25 s, recorded answer thumbs-down, step and status CANCELED, restarts from `main`, COMPLETED |
