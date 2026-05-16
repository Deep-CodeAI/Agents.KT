# `agents_engine/model/LlmChunk.kt` — provider-level streaming chunk

A narrow sealed-interface union over the deltas a streaming LLM provider emits during a single chat round-trip (#1722). Nothing here references agentic concepts (`skillName`, `agentId`) — those belong upstream in `AgentEvent`.

## Variants

```kotlin
sealed interface LlmChunk {
    data class TextDelta(val text: String)                                          // model content
    data class ToolCallStarted(val callId: String, val toolName: String)            // tool call beginning
    data class ToolCallArgumentsDelta(val callId: String, val deltaJson: String)    // partial args JSON
    data class ToolCallFinished(val callId: String, val arguments: Map<String, Any?>) // fully parsed args
    data class End(val tokenUsage: TokenUsage?)                                     // terminal — one per round-trip
}
```

## Flow shape

```
[TextDelta]* [ToolCallStarted (ArgumentsDelta)* ToolCallFinished]* End
```

- Multiple `TextDelta`s may interleave with tool-call sequences (the LLM streams content before, after, and between tool calls).
- Each tool call sequence is `Started → Δ* → Finished`.
- `End` always fires last and carries the per-round-trip `TokenUsage` (or null).

## Non-streaming providers

`ModelClient.chatStream` default impl wraps `chat()`:
- `LlmResponse.Text` → one `TextDelta(fullContent)` + `End(tokenUsage)`.
- `LlmResponse.ToolCalls` → for each call: `Started → ArgumentsDelta(rawArguments ?: "") → Finished(arguments)`, then one `End`.

`callId` is honored from `ToolCall.callId` when set; synthesized via `UUID.randomUUID()` only when the non-streaming `chat()` returned no id (#1739). Keeps explicit IDs stable end-to-end so `AgentEvent.ToolCallStarted` and `ToolCallFinished` match.

## Where consumers project these into `AgentEvent`

`AgenticLoop.kt` (when an `emitter` is set) reads `Flow<LlmChunk>` and emits `AgentEvent.Token` per `TextDelta`, `AgentEvent.ToolCallStarted` per `Started`, etc. The IDs are passed through.

## Related files

- `ModelClient.kt` — the `chatStream` entry point and default-impl wrapper.
- `ClaudeClient.kt` / `OllamaClient.kt` / `OpenAiClient.kt` — adapters that override `chatStream` for native streaming.
- `runtime/events/AgentEvent.kt` — the consumer-level streaming surface built atop these.
- `model/StreamingAggregator.kt` — helper that collects chunks back into an `LlmResponse`.
