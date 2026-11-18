# Event schema v1.1 and the portfolio adapter

The JSON Schema is `agents/schema/events.schema.json`. Its `schema_version` is
`1.1`; the optional event `schema_version` identifies newly emitted events. All
v1.0 event kinds, required fields and client messages remain accepted.

Additions: optional `project` (station ID) and `project_name` on every event;
`decision` with `decision_id`, `title`, `options`, `blocking`, optional
`default_option`, `recommendation`, `deadline_ms` and `evidence`; `metric` with
`name` and numeric `value`; `budget` with `pct` and `pace_pct` (percentage points).
A client `input_response` may carry `decision_id` instead of `step_id`.
Legacy step-based answers remain valid. A resolved decision is a `status` event
with `decision_id`, optional `answer` and the current agent status.

Run from `agents/bridge` (Node >=22):

```
node --experimental-strip-types src/autoflow.ts --live
node --experimental-strip-types src/autoflow.ts --record ../../runs/bridge/capture.jsonl --duration 60
node --experimental-strip-types src/autoflow.ts --live --record ../../runs/bridge/capture.jsonl --duration 60
```

`--url` defaults to `http://127.0.0.1:8790`; `--port` defaults to 8765.
The adapter polls `/api/state` immediately and every two seconds after each
response, retrying transient failures. SSE `/api/stream` carries the same shape;
polling was chosen to reuse snapshot delta conversion and recover on reconnect.
The first poll emits the current state and retained event history, subsequent
polls emit only changed fields/new events. API event timestamps are ordered by
source ID; wire `t_ms` is observation time since adapter start, not historical
time. Use the recorded-real-day task for a historical time-faithful replay.

Each project has a stable opaque station ID (SHA-256 prefix of its source ID).
The Chief uses station `chief`. Agent IDs are similarly opaque; only the
adapter holds the reverse map for POSTs. Known project display names are neutral
aliases, and Heads/workers are named by role. Unclaimed tasks are omitted;
claimed tasks produce `task_assigned` plus progress messages and state. Idle
agents map to COMPLETED (no active work); parked/blocked tasks map to SUBMITTED.
Open decisions emit `decision` and a legacy INPUT_REQUIRED status; task/agent
updates cannot clear that status while an ask is open. Answered decisions emit
one resolved status. The live API only lists open decisions, so disappearance
resolves an ask without inventing an answer. Multiple open asks keep the agent
INPUT_REQUIRED. Metrics come from the last project series value and numeric
task metrics; plans use component names/statuses; weekly budget fractions are
converted to percentage points.

All string values, including nested option labels, pass the existing path,
username and key redactor and task #73's shared brand denylist. Brand words are
replaced with `product`; any remaining denylist match drops the whole event.
Missing option lists are counted as dropped rather than inventing user choices.
No deadline is invented when the source lacks one. Provider-specific budgets,
plan graph edges, allocations, raw event data and other dashboard-only metadata
are not represented by v1.1. Evidence text is redacted/truncated to 280 characters.

Only inbound, schema-validated WebSocket messages call write-back. Answers must
identify a currently open decision, its asking agent and an offered option ID;
the POST uses the *original* option label even if its displayed copy was scrubbed.
The tray may message known workers via `/api/agents/{id}/message` with `{text}`.
Unknown targets and rejected endpoints are logged. Polling and recording never
POST, and tests use an ephemeral localhost stub, never the real decisions API.

`src/autoflow_sample.ts` regenerates `agents/fixtures/autoflow_sample.jsonl` from
the two sanitised API samples under `test/data/`. Its terminal snapshot is a
sample completion for legacy replay smoke checks, not a claim about real work.
Only the sample aliases the Chief and first project Head/worker to the three
legacy replay lanes. Other stations stay in the fixture but the current WebXR
reader ignores them; rendering all live stations belongs to task #88.
