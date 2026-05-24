---
description: Source-file knowledge for agents_engine/model/OllamaClient.kt — local Ollama HTTP adapter (default ModelClient). POST /api/chat at localhost:11434, OpenAI-style tool schema, JsonSchema constrained decoding via Ollama format field (#1949), parseToolArguments handling Map / JSON-string / null shapes, NDJSON streaming, LlmProviderException on errors (#702), open sendChat seam. Call when the IDE LLM needs to reason about local LLM integration.
---

# `agents_engine/model/OllamaClient.kt` — local Ollama HTTP adapter

The framework's default `ModelClient`. Targets a local Ollama daemon on `localhost:11434` by default.

## Construction

```kotlin
agent<X, Y>("...") {
    model { ollama("gpt-oss:120b-cloud") }   // host:port defaults
}
```

Override host/port via the agent's `model { }` builder. For testing, pass a custom `client` directly (bypassing the lazy auto-construction).

## Wire mapping (LlmMessage ↔ Ollama JSON)

`POST /api/chat`:
- `LlmMessage("system", text)` → `{role: "system", content: text}` (in messages array).
- `LlmMessage("user", text)` → `{role: "user", content: text}`.
- `LlmMessage("assistant", "", toolCalls=...)` → `{role: "assistant", content: "", tool_calls: [...]}`.
- `LlmMessage("tool", text)` → `{role: "tool", content: text}` paired in-order to the prior assistant's `tool_calls`.

Tool defs → `[{type: "function", function: {name, description, parameters}}]` — OpenAI-style schema (Ollama emulates OpenAI's tool shape).

## Constrained Decoding

When `chat(messages, jsonSchema)` receives a non-null schema, `buildRequestJson` includes:

```json
{"format": {"type": "object", "properties": {...}}}
```

Ollama treats `format` as an inline JSON Schema for the assistant response. The field is also carried on streaming requests.

## Argument parsing (`parseToolArguments`)

Ollama sometimes returns `tool_calls[].function.arguments` as:
- A `Map<*, *>` — used as-is.
- A JSON string — parsed via lenient parser, with parse errors surfaced in `ParsedToolArguments.parseError` for the agentic loop's argument-repair path.
- `null` or empty string — empty args map.

## Streaming

`chatStream` reads NDJSON from `POST /api/chat` with `stream=true`, emitting `LlmChunk.TextDelta` per non-empty content delta and `End(tokenUsage)` once `done=true` arrives.

## Error boundary

Top-level `{"error": "..."}` envelopes surface as `LlmProviderException` (#702). Non-2xx HTTP responses too — message names the endpoint and status.

## Test seam

`sendChat(body)` is `open` so subclasses can inject fake responses without touching HTTP.

## Related files

- `ModelClient.kt` — the interface implemented.
- `OllamaPreflight.kt` — reachability check (use in `LiveRunner.precheck`).
- `LlmProviderException.kt` — the boundary error.
- `generation/LenientJsonParser.kt` — tolerant args parser.
