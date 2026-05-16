# `agents_engine/model/OpenAiClient.kt` — OpenAI Chat Completions adapter (#1656)

One of the three shipped `ModelClient` implementations. Wraps OpenAI's `POST /v1/chat/completions`.

## Construction

```kotlin
agent<X, Y>("...") {
    model {
        openai("gpt-4o-mini")
        apiKey = System.getenv("OPENAI_API_KEY")
        openAiBaseUrl = "https://api.openai.com"  // override for Azure / regional / proxy
        temperature = 0.7
        maxTokens = 4096
    }
}
```

## Wire mapping (LlmMessage ↔ OpenAI JSON)

| `LlmMessage` | OpenAI JSON |
|---|---|
| `("system", text)` | `{role: "system", content: text}` — kept in the messages array (unlike Anthropic's hoisted `system` field). |
| `("user", text)` | `{role: "user", content: text}` |
| `("assistant", "", toolCalls=[...])` | `{role: "assistant", content: null, tool_calls: [{id: "call_<n>", type: "function", function: {name, arguments: "<stringified JSON>"}}]}` |
| `("tool", text)` | `{role: "tool", tool_call_id: "call_<n>", content: text}` paired in-order to the prior assistant's `tool_calls`. |

**Stringified arguments.** OpenAI's wire convention puts `arguments` as a stringified JSON, not an object. The framework `JSON.stringify`s args on the way out and `LenientJsonParser.parse`s on the way in.

**Synthetic call IDs.** Generated per request as `call_<n>` — `ToolCall` doesn't carry a provider id, and IDs only need to be unique within one request scope.

## Tool definitions

```json
{"type": "function", "function": {"name": "...", "description": "...", "parameters": {...}}}
```

OpenAI's `parameters` (not Anthropic's `input_schema`). Built from `agents_engine.generation.jsonSchema(toolDef.argType)`.

## Streaming

`chatStream` reads OpenAI's SSE format (`data: {...}\n\n` lines, `data: [DONE]` terminator):
- `choices[0].delta.content` → `LlmChunk.TextDelta`.
- `choices[0].delta.tool_calls[].id` + `function.name` → `LlmChunk.ToolCallStarted` (first delta only).
- `choices[0].delta.tool_calls[].function.arguments` → `LlmChunk.ToolCallArgumentsDelta` (subsequent deltas).
- Terminal `usage` field → `LlmResponse.tokenUsage`.

## Error boundary

OpenAI's `{"error": {"message": "...", "type": "...", ...}}` surfaces as `LlmProviderException` (#702 — same contract as the other adapters).

## Test seam

`sendChat(body, headers)` is `open`. Used by `OpenAiClientTest` / `OpenAiClientStreamingTest`.

## Related files

- `ModelClient.kt` — the interface implemented.
- `OllamaClient.kt`, `ClaudeClient.kt` — sibling adapters with different wire conventions.
- `LlmChunk.kt` — the streaming chunk types.
- `generation/jsonSchema.kt` — generates `parameters` for tools.
