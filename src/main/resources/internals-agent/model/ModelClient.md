# `agents_engine/model/ModelClient.kt` — LLM transport interface

The seam between the framework and the underlying LLM provider. Three implementations ship with the framework: `OllamaClient`, `ClaudeClient`, `OpenAiClient`. Users plug in their own by implementing the `ModelClient` `fun interface`.

## The interface

```kotlin
fun interface ModelClient {
    fun chat(messages: List<LlmMessage>): LlmResponse

    suspend fun chatStream(messages: List<LlmMessage>): Flow<LlmChunk> = /* default impl wraps chat */
}
```

`fun interface` — a single-method SAM. Custom clients can be written as a single-line lambda for tests.

## Shared types

| Type | Purpose |
|---|---|
| `LlmMessage(role, content, toolCalls?)` | A single turn: role is `"system"`, `"user"`, `"assistant"`, or `"tool"`. `toolCalls` set on assistant turns that called tools. |
| `ToolCall(name, arguments, rawArguments?, invalidArgumentsError?, callId?)` | One tool invocation. `rawArguments` is the LLM's raw JSON. `invalidArgumentsError` carries parse errors back for argument repair. `callId` (#1739) lets streaming chunks correlate to one started/finished pair. |
| `TokenUsage(promptTokens, completionTokens)` | Per round-trip usage; `total = prompt + completion` counts toward `BudgetConfig.maxTokens` (#963). |
| `LlmResponse.Text(content, tokenUsage?)` | Model produced text. |
| `LlmResponse.ToolCalls(calls, tokenUsage?)` | Model produced tool calls (no text). |

`tokenUsage` is nullable on the response — only present when the provider reports it. Turns with null usage count zero toward `maxTokens`.

## Default `chatStream` impl

Wraps `chat` so non-streaming providers work without overriding:

- `LlmResponse.Text` → `TextDelta(content) + End(tokenUsage)`.
- `LlmResponse.ToolCalls` → for each call: `Started(callId) → ArgumentsDelta(rawArguments ?: "") → Finished(arguments)`, then one `End`.

`callId` is taken from the `ToolCall` when present; otherwise synthesized via `UUID.randomUUID()` — keeping explicit IDs stable end-to-end across streaming and non-streaming paths.

## Writing a custom client

```kotlin
val myClient = ModelClient { messages ->
    val raw = http.post(myEndpoint, body = serialize(messages))
    when {
        raw.toolCalls.isNotEmpty() -> LlmResponse.ToolCalls(raw.toolCalls.map { /* ... */ })
        else -> LlmResponse.Text(raw.content, TokenUsage(raw.promptTokens, raw.completionTokens))
    }
}
```

Add streaming by overriding `chatStream` to surface partial chunks. The framework only requires `chat` — streaming is optional.

## Error contract

- Provider-level failures → `LlmProviderException` (auth, capability, model-not-found, malformed request).
- Output parsing failures (from `transformOutput`) → `IllegalStateException` or whatever the transformer throws.

## Related files

- `ClaudeClient.kt`, `OllamaClient.kt`, `OpenAiClient.kt` — shipped implementations.
- `LlmChunk.kt` — the streaming chunk types.
- `LlmProviderException.kt` — the boundary error.
- `StreamingAggregator.kt` — collects a `Flow<LlmChunk>` back into an `LlmResponse`.
- `AgenticLoop.kt` — the consumer that runs `chat`/`chatStream` per turn.
