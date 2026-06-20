---
description: Source-file knowledge for agents_engine/agui/AgUiServer.kt + AgUiEventBridge.kt — AG-UI serving (#4523, PRD §12.7). AgUiServer.from(agent) exposes an agent over the AG-UI protocol (agent↔frontend, e.g. CopilotKit): POST a RunAgentInput, get an SSE stream of typed AG-UI events. Not a descriptor exporter — a runtime bridge over the streaming AgentSession. AgUiEventBridge maps AgentEvent -> AG-UI events (RUN_/TEXT_MESSAGE_/TOOL_CALL_/STEP_ families) in the RUN_STARTED…RUN_FINISHED envelope. Same from(agent) loopback+bearer posture as McpServer/A2AServer/NlWebServer; hand-rolled, no AG-UI SDK. Call when the IDE LLM reasons about serving an agent to a frontend UI.
---

# `agents_engine/agui/AgUiServer.kt` — serve an agent to a frontend (AG-UI, #4523)

The **fourth** agentic-web serve surface beside `McpServer` (agent↔tools), `A2AServer` (agent↔agent), and
`NlWebServer` (agent↔web-content). AG-UI is **agent↔user/frontend** — the only one that reaches an end-user UI
(a streaming CopilotKit/React chat) without us building a frontend.

```kotlin
val server = AgUiServer.from(agent, port = 8765, bearerToken = secret).start()
// POST a RunAgentInput to server.url -> SSE stream of AG-UI events ; server.stop() when done
```

## Not a descriptor exporter — a runtime bridge

`agent.json` / AgentCard / OASF record are *static descriptions*. AG-UI is a **runtime streaming surface**: a
single `POST` of `RunAgentInput {threadId, runId, state, messages[], tools[], context[]}` returns an **SSE
stream of typed events**. The new user turn is the **last `user` message's content** (the agent input);
`AgUiServer` runs `agent.session(input)` and pipes its [AgentEvent] stream through [AgUiEventBridge].

## AgUiEventBridge — the AgentEvent → AG-UI mapping

| AG-UI | from AgentEvent |
|---|---|
| `RUN_STARTED` / `RUN_FINISHED` / `RUN_ERROR` | session open / `Completed` / `Failed` |
| `TEXT_MESSAGE_START/CONTENT/END` | `Token` (START lazily on first token; END before any tool call / step finish / run end) |
| `TOOL_CALL_START/ARGS/END` | `ToolCallStarted` / `ToolCallArgumentsDelta` / `ToolCallFinished` |
| `TOOL_CALL_RESULT` | `ToolCallFinished` (the executor return — `content` = stringified `result`, `role: tool`, `isError`, a fresh tool-message `messageId`) |
| `REASONING_START / _MESSAGE_START / _MESSAGE_CONTENT / _MESSAGE_END / _END` | `Reasoning` (#4629 — live model thinking; `THINKING_*` deprecated) |
| `STEP_STARTED/FINISHED` | `SkillStarted` / `SkillCompleted` |

The bridge holds the small **text-message state machine** that guarantees AG-UI's ordering
(`START → CONTENT* → END`, and text always closes before a tool call / step finish / run end). A
deterministic skill streams no `Token`s, so `finish()` surfaces the final output as one text message — a UI
always has something to render. `ToolCallFinished` emits `TOOL_CALL_END` then `TOOL_CALL_RESULT` (the executor
return). Events still not surfaced (`ModelTurn*`, `Stage*`) map to nothing.

## Posture — same as the other serve surfaces

Loopback JDK `HttpServer` on `127.0.0.1`, `POST /agent`, optional `Authorization: Bearer`; front with a TLS
gateway for network reach. SSE is hand-rolled (`Content-Type: text/event-stream`, `data: <json>\n\n`, flush
per event) — **no AG-UI SDK** (the community JVM SDKs are client-side only, they consume a stream, not serve).
Lives in core (no external deps), so it uses internal `McpJson` / `LenientJsonParser`.

## Scope / follow-ups (epic #4523)

Shipped: lifecycle/text/tool/step families + REASONING (#4629 — `AgentEvent.Reasoning` → `REASONING_START` →
`REASONING_MESSAGE_START` → `REASONING_MESSAGE_CONTENT`* → `REASONING_MESSAGE_END` → `REASONING_END`, one
`messageId`; the `THINKING_*` names are deprecated). The bridge opens the block on the first reasoning chunk and
closes it before any answer token / tool call / step finish / run finish (`closeStreams()`). `TOOL_CALL_RESULT`
carries the executor return after each `TOOL_CALL_END`. Follow-ups: STATE_SNAPSHOT/STATE_DELTA (needs a shared
agent↔UI state model we don't have yet) and client-tool round-trips — both blocked on the same runtime seam: the
next `POST` re-sends the full `messages[]` history, but `session(input)` only takes the **last user message** and
exposes no `resumeFrom`/`seedMessages` hook to replay prior turns (and a client tool needs the run to emit-and-stop
awaiting a `ToolMessage`). Wiring AG-UI onto the snapshot/resume seam (`RunRequest.resumeFrom`) is the prerequisite.

## Related

- `nlweb/NlWebServer.kt`, `a2a/A2AServer.kt`, `mcp/McpServer.kt` — the structural siblings (`from(agent)`).
- `runtime/events/AgentEvent.kt` + `AgentSessionExtension.kt` (`session(input)`) — the source stream.
