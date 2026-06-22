# AG-UI — serve an agent to a frontend (#4523)

[← Back to README](../README.md)

AG-UI is the **agent↔user/frontend** layer of the agentic web (MCP = agent↔tools, A2A = agent↔agent,
NLWeb = agent↔web-content, **AG-UI = agent↔user**). `AgUiServer.from(agent)` exposes any `Agent<IN, OUT>`
over the [AG-UI protocol](https://github.com/ag-ui-protocol/ag-ui): a single `POST` of a `RunAgentInput`
returns an **SSE stream of typed AG-UI events**, bridged live from the agent's streaming `AgentSession`. It is
the only interop surface that reaches an end-user UI (e.g. a CopilotKit React chat) without you building a
frontend. Same `from(agent)` shape, loopback bind, and optional bearer auth as `McpServer` / `A2AServer` /
`NlWebServer`; hand-rolled over the JDK `HttpServer`, no AG-UI SDK.

## Serve

```kotlin
val server = AgUiServer.from(agent, port = 8765, bearerToken = System.getenv("AGUI_TOKEN")).start()
// POST a RunAgentInput to server.url ->  SSE stream of AG-UI events ;  server.stop() when done
```

`RunAgentInput` is `{ threadId, runId, messages, tools, state, context }`. The **new user turn is the last
`user` message's content** (the agent input); the reply is an SSE stream wrapped in the
`RUN_STARTED … RUN_FINISHED` (or `RUN_ERROR`) envelope.

```bash
curl -N http://localhost:8765/agent \
  -H 'Authorization: Bearer '"$AGUI_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"threadId":"t1","runId":"r1","messages":[{"role":"user","content":"classify this ticket: card declined"}]}'
```

## The event stream

Each `AgentEvent` from the session maps to AG-UI events, in order:

| AG-UI event(s) | from `AgentEvent` | when |
|---|---|---|
| `RUN_STARTED` / `RUN_FINISHED` / `RUN_ERROR` | session open / `Completed` / `Failed` | run envelope |
| `TEXT_MESSAGE_START` → `…_CONTENT`* → `…_END` | `Token` | streamed answer text |
| `REASONING_START` → `…_MESSAGE_START` → `…_MESSAGE_CONTENT`* → `…_MESSAGE_END` → `…_END` | `Reasoning` | live model thinking (#4629; `THINKING_*` deprecated) |
| `TOOL_CALL_START` → `TOOL_CALL_ARGS`* → `TOOL_CALL_END` → **`TOOL_CALL_RESULT`** | `ToolCallStarted` / `…ArgumentsDelta` / `…Finished` | a tool call + its **return** |
| `STEP_STARTED` / `STEP_FINISHED` | `SkillStarted` / `SkillCompleted` | skill boundaries |

The bridge holds a small state machine that guarantees AG-UI ordering: text opens lazily on the first token
and closes before any tool call / step finish / run end; a reasoning block opens on the first `Reasoning` chunk
and closes before the answer.

### Tool calls now carry their result (#4680)

`TOOL_CALL_END` closes the call; **`TOOL_CALL_RESULT`** immediately follows with what the tool returned, so a
frontend can render outputs, not just invocations:

```jsonc
{ "type": "TOOL_CALL_START",  "toolCallId": "c1", "toolCallName": "lookup_account" }
{ "type": "TOOL_CALL_ARGS",   "toolCallId": "c1", "delta": "{\"id\":42}" }
{ "type": "TOOL_CALL_END",    "toolCallId": "c1" }
{ "type": "TOOL_CALL_RESULT", "toolCallId": "c1", "messageId": "…",
  "content": "{\"status\":\"past_due\"}", "role": "tool", "isError": false }
```

`content` is the stringified executor return; `isError` flags an executor failure the loop swallowed; the fresh
`messageId` ties the result to a tool-role message.

## Live reasoning (#4629)

If the model emits a thinking stream (`model { reasoning(...) }` — Claude / DeepSeek / Ollama), AG-UI surfaces
it as the `REASONING_*` family so a chat can show the model thinking instead of a spinner. Reasoning always
precedes and closes before the answer text.

## Monetize it

`AgUiServer.from(agent, payment = gate)` fronts the endpoint with an [x402](x402.md) payment gate so the agent
charges per run — see [x402.md](x402.md).

## Posture

Binds `127.0.0.1` only (front with a TLS gateway for network reach); `bearerToken` requires
`Authorization: Bearer …`; 1 MiB request cap; `405` for non-`POST`.

## Not yet (documented follow-ups)

`STATE_SNAPSHOT` / `STATE_DELTA` (shared agent↔UI state) and client-tool round-trips (the client executes a
tool and returns a `ToolMessage` on the next `POST`) are deferred — both need `session(input)` to expose a
`resumeFrom` / `seedMessages` seam to replay prior `messages[]`, which is a separate runtime change.

## Related

- [streaming.md](streaming.md) — the `AgentSession` event model this bridges.
- [mcp-server.md](mcp-server.md) / [a2a.md](a2a.md) — the structural sibling serve surfaces.
