# Agent event schema (v0)

`events.schema.json` (JSON Schema draft-07) defines two things:

- **`event`**: server to headset. One flat object per JSONL line or WebSocket text frame.
- **`client_message`**: headset to server, over the same socket.

## Event fields

| field | type | notes |
|---|---|---|
| `t_ms` | int | ms since run start. Non-decreasing on the wire (the bridge clamps). Required. |
| `agent_id` | string | `researcher` / `builder` / `reviewer` in fixtures, `bridge` for bridge errors. Required. |
| `kind` | enum | see below. Required. |
| `agent_name` | string | display name, sent on the agent's `task_assigned` |
| `run_id` | string | a new value means the headset can clear its graph (`--loop` replays reuse the same value) |
| `step_id` / `parent_step_id` | string / null | DAG node and tree parent. The agent root is `<agent>.root` with a null parent. |
| `depends_on` | string[] | extra edges, on `step_start` only. Used for **fan-in**, and edges may cross agents (Reviewer waits on Builder). |
| `retry_of`, `attempt` | string, int | on a `step_start` that retries a FAILED step |
| `title` | string | ≤ ~60 chars, for a panel row or node label |
| `detail` | string | ≤ 280 chars: tool input, result excerpt or message body |
| `status` | enum | A2A vocabulary: `SUBMITTED WORKING INPUT_REQUIRED COMPLETED FAILED CANCELED` |
| `tool` | string | on `tool_call`/`tool_result`: Read, Edit, Bash, Grep, WebSearch, ... |
| `options`, `default_option` | | on `status=INPUT_REQUIRED`: `[{id,label,gesture}]`, where gesture is one of `thumbs_up thumbs_down swipe_left swipe_right pinch palm_open` |
| `answer` | string | on the `status=WORKING` that resumes after INPUT_REQUIRED |
| `plan` | string[] | on `kind=plan` |
| `priority` | `low normal high` | on `task_assigned` |

## Kinds and what the headset does with them

- `task_assigned`: a task card appears on the agent's panel (title = task).
- `status`: the agent-level state (the panel colour). On `INPUT_REQUIRED`, show the prompt and options and wait for a gesture.
- `step_start`: add a graph node with an edge from `parent_step_id` and each `depends_on`.
- `step_end`: the node turns COMPLETED (green) or FAILED (red).
- `tool_call` / `tool_result`: the activity ticker inside a node, which does not create new nodes.
- `plan`: a checklist on the panel.
- `message`: the agent's speech bubble.
- `artifact`: a file or commit chip.
- `error`: a red toast.
- `done`: the run finished.

**Retry pattern:** `step_end{FAILED}`, then `error`, then `step_start{retry_of, attempt:2}`. Draw the retry node next to the failed one.

**Input pattern:** `status{INPUT_REQUIRED, step_id, options}`. That agent's stream pauses until the headset sends an `input_response`. Then comes `status{WORKING, answer}`. In replay mode the bridge does the pausing, and other agents keep running.

## Headset to server

```json
{"kind":"task_assigned","agent_id":"builder","task":"Update the changelog","priority":"high"}
{"kind":"input_response","agent_id":"reviewer","step_id":"v3","option_id":"approve"}
```

The bridge echoes `task_assigned` to every client as an event, which doubles as the acknowledgement. An invalid message gets back an `error` event from `agent_id: "bridge"`.

## Known limits (push back here)

- A fixture is a recording, so the answer to INPUT_REQUIRED does not change what happens next. Branch-on-answer would need two recorded continuations per question.
- In live `claude -p` mode an `input_response` cannot be injected into the running agent; it is only logged. Real approval needs a `PermissionRequest` HTTP hook that blocks on the headset, which is not built yet.
- Real runs have wide, shallow graphs: every tool call is a child of the root, and each assistant turn depends on the previous turn's tools. In the real fixture the root has 13 children, so the headset should collapse or cluster siblings.
- Newer Claude Code uses `TaskCreate`/`TaskUpdate` instead of `TodoWrite`. Those currently become ordinary steps, not `plan` events.
