---
description: Source-file knowledge for agents_engine/model/ModelClient.kt — the LLM transport fun interface and shared types (LlmMessage, ToolCall with callId #1739, JsonSchema #1949, TokenUsage #963, LlmResponse.Text/ToolCalls). Default chatStream wraps non-streaming chat with LlmChunk emission. Schema-aware chat(messages, jsonSchema) preserves SAM compatibility. Three shipped impls: Ollama, Claude, OpenAI. Call when the IDE LLM needs to reason about adding a new LLM provider or testing with a fake client.
---

# `agents_engine/model/ModelClient.kt` — LLM transport interface

The seam between the framework and the underlying LLM provider. Three implementations ship with the framework: `OllamaClient`, `ClaudeClient`, `OpenAiClient`. Users plug in their own by implementing the `ModelClient` `fun interface`.

## The interface

```kotlin
fun interface ModelClient {
    fun chat(messages: List<LlmMessage>): LlmResponse
    fun chat(messages: List<LlmMessage>, jsonSchema: JsonSchema?): LlmResponse = chat(messages)
    fun supportsConstrainedDecoding(): Boolean = false

    suspend fun chatStream(messages: List<LlmMessage>): Flow<LlmChunk> = /* default impl wraps chat */
    suspend fun chatStream(messages: List<LlmMessage>, jsonSchema: JsonSchema?): Flow<LlmChunk> = /* wraps schema-aware chat */
}
```

`fun interface` — a single-method SAM. The one-argument `chat` remains the sole abstract method, so custom clients can still be written as a single-line lambda for tests. Providers that support constrained decoding override the schema-aware overload and return `true` from `supportsConstrainedDecoding()`.

## Shared types

| Type | Purpose |
|---|---|
| `LlmMessage(role, content, toolCalls?)` | A single turn: role is `"system"`, `"user"`, `"assistant"`, or `"tool"`. `toolCalls` set on assistant turns that called tools. |
| `ToolCall(name, arguments, rawArguments?, invalidArgumentsError?, callId?)` | One tool invocation. `rawArguments` is the LLM's raw JSON. `invalidArgumentsError` carries parse errors back for argument repair. `callId` (#1739) lets streaming chunks correlate to one started/finished pair. |
| `JsonSchema(name, schema)` | Provider-neutral structured-output request. `schema` is raw JSON Schema text; adapters translate it to OpenAI `response_format`, Ollama `format`, or Anthropic's structured-output tool. |
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

For constrained decoding, override:

```kotlin
override fun supportsConstrainedDecoding() = true
override fun chat(messages: List<LlmMessage>, jsonSchema: JsonSchema?): LlmResponse {
    // embed jsonSchema?.schema in the provider request when non-null
}
```

## Error contract

- Provider-level failures → `LlmProviderException` (auth, capability, model-not-found, malformed request).
- Output parsing failures (from `transformOutput`) → `IllegalStateException` or whatever the transformer throws.

## Related files

- `ClaudeClient.kt`, `OllamaClient.kt`, `OpenAiClient.kt` — shipped implementations.
- `LlmChunk.kt` — the streaming chunk types.
- `LlmProviderException.kt` — the boundary error.
- `StreamingAggregator.kt` — collects a `Flow<LlmChunk>` back into an `LlmResponse`.
- `AgenticLoop.kt` — the consumer that runs `chat`/`chatStream` per turn.
