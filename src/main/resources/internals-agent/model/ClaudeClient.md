---
description: Source-file knowledge for agents_engine/model/ClaudeClient.kt — Anthropic Messages API adapter (#1644). LlmMessage→Anthropic JSON wire mapping (system field, tool_use/tool_result blocks with synthetic toolu_<n> IDs, input_schema spelling), streaming via SSE (text_delta and input_json_delta chunks), boundary errors via LlmProviderException (#702), open sendChat seam for tests. Call when the IDE LLM needs to reason about wiring the framework to Anthropic.
---

# `agents_engine/model/ClaudeClient.kt` — Anthropic Messages adapter (#1644)

One of the three shipped `ModelClient` implementations. Wraps Anthropic's `POST /v1/messages` API.

## Construction

```kotlin
agent<X, Y>("...") {
    model {
        claude(
            apiKey = System.getenv("ANTHROPIC_API_KEY"),
            model = "claude-opus-4-7-20250514",
            temperature = 0.7,
            maxTokens = 4096,
        )
    }
}
```

Defaults:
- `baseUrl = "https://api.anthropic.com"`
- `anthropicVersion = "2023-06-01"`
- `temperature = 0.7`, `maxTokens = DEFAULT_MAX_TOKENS`
- `requestTimeout`, `connectTimeout`, `maxResponseBytes` — sensible production defaults

## Wire mapping (LlmMessage ↔ Anthropic JSON)

| `LlmMessage` | Anthropic JSON |
|---|---|
| `("system", text)` | Top-level `system` field on the request (NOT a message). |
| `("user", text)` | `{role: "user", content: text}` |
| `("assistant", "", toolCalls=[...])` | `{role: "assistant", content: [{type: "tool_use", id: "toolu_<n>", name, input}, ...]}` |
| `("tool", text)` | Wrapped as `{role: "user", content: [{type: "tool_result", tool_use_id, content: text}]}`, paired in-order to the prior assistant's `tool_use` blocks. |

Synthetic tool-use IDs are generated per request — `ToolCall` doesn't carry a provider id, and the ids only need to be unique within one request scope.

## Tool definitions

```json
{"name": "...", "description": "...", "input_schema": {...}}
```

Anthropic's `input_schema` (not OpenAI's `parameters`). Built from `agents_engine.generation.jsonSchema(toolDef.argType)`.

## Streaming

`chatStream(messages)` returns `Flow<LlmChunk>`, parsing Anthropic's SSE format:
- `message_start` / `content_block_start` / `content_block_delta` / `content_block_stop` / `message_delta` / `message_stop` event types.
- `text_delta` deltas become `LlmChunk.TextDelta`.
- `input_json_delta` on tool-use content blocks become `LlmChunk.ToolCallArgumentsDelta`.
- Final usage from `message_delta` populates `LlmResponse.tokenUsage`.

## Error boundary

Top-level `{"type": "error", "error": {...}}` envelopes surface as `LlmProviderException` (#702 — same contract as `OllamaClient`). Includes HTTP status, error type, and message.

## Test seam

`sendChat(body, headers)` is `open` — subclasses can override it to inject mock responses without touching the HTTP layer. Used heavily in `ClaudeClientTest` / `ClaudeClientStreamingTest`.

## Related files

- `ModelClient.kt` — the interface this implements.
- `OllamaClient.kt`, `OpenAiClient.kt` — sibling implementations.
- `LlmChunk.kt` — the streaming chunk types.
- `LlmProviderException.kt` — the boundary error type.
- `generation/jsonSchema.kt` — generates `input_schema` for tools.
