---
description: Source-file knowledge for agents_engine/model/StreamingAggregator.kt — chatOrStream entry point (#1739) the agentic loop calls per turn. emitter==null → client.chat() unchanged; emitter!=null → collect client.chatStream() while emitting AgentEvent.Token / ToolCallStarted / ToolCallArgumentsDelta, rebuild LlmResponse with stable callIds. AgentEventEmitter typealias (non-suspend per #1745). ToolCallFinished fires later in the loop with executor result. Interleaving-safe via callId routing. Call when the IDE LLM needs to reason about streaming plumbing.
---

# `agents_engine/model/StreamingAggregator.kt` — chat-or-stream entry point

A single internal `suspend fun chatOrStream(client, messages, agentId, skillName, emitter): LlmResponse` plus the `AgentEventEmitter` typealias. The agentic loop calls this once per turn.

## The typealias

```kotlin
internal typealias AgentEventEmitter = (AgentEvent<*>) -> Unit
```

- `AgentEvent<*>` because the loop only ever emits non-`OUT` subtypes (`Token`, `ToolCall*`, `SkillStarted`, `SkillCompleted`, `Failed`). The typed `AgentEvent.Completed<OUT>` is emitted in `Agent.session(input)` AFTER the loop returns.
- Non-suspend (#1745) so it can be called from non-suspend callbacks like `Agent.invokeSuspendForSession`'s `onSkillStarted` lambda.
- Implementations typically `channel.trySend(event)` — non-blocking, appropriate for `BUFFERED`-channel-backed `Flow`s.

## Behavior

```kotlin
internal suspend fun chatOrStream(
    client: ModelClient,
    messages: List<LlmMessage>,
    agentId: String,
    skillName: String,
    emitter: AgentEventEmitter?,
): LlmResponse
```

- `emitter == null` → returns `client.chat(messages)` directly via `Dispatchers.IO`. Byte-for-byte the legacy non-streaming path.
- `emitter != null` → collects `client.chatStream(messages)`, emits AgentEvents, rebuilds an `LlmResponse`.

## Aggregation rules

| Incoming `LlmChunk` | Emitted `AgentEvent` | Aggregation effect |
|---|---|---|
| `TextDelta(text)` | `Token(agentId, skillName, text)` | `textBuilder.append(text)` |
| `ToolCallStarted(callId, toolName)` | `ToolCallStarted(agentId, skillName, callId, toolName)` | record in `callOrder` + `pendingNames` |
| `ToolCallArgumentsDelta(callId, deltaJson)` | `ToolCallArgumentsDelta(agentId, callId, deltaJson)` | passthrough |
| `ToolCallFinished(callId, arguments)` | — | record final args in `pendingArgs` (no consumer-facing event yet) |
| `End(tokenUsage)` | — | record `tokenUsage` for the rebuilt `LlmResponse` |

**No `AgentEvent.ToolCallFinished` is emitted here** — that event needs the executor's `result`, which the agentic loop produces AFTER `chatOrStream` returns. The loop emits it then.

## Rebuilt response

- If `callOrder` is non-empty → `LlmResponse.ToolCalls(calls, tokenUsage)`. Each `ToolCall` carries the explicit `callId` so the loop can correlate `ToolCallFinished` back to the same id.
- Otherwise → `LlmResponse.Text(textBuilder.toString(), tokenUsage)`.

## Interleaving safety

Even when a provider's streaming adapter interleaves chunks across multiple tool calls (Anthropic SSE does this), the `callId` field on each chunk routes the delta to the right `pendingNames` / `pendingArgs` entry. End-to-end stable IDs are critical for downstream UI consumers reconstructing tool-call timelines.

## Related files

- `LlmChunk.kt` — incoming chunk types.
- `runtime/events/AgentEvent.kt` — emitted event types.
- `AgenticLoop.kt` — the sole caller.
- `ModelClient.kt` — `chat` / `chatStream` source.
