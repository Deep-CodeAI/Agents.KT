---
description: Source-file knowledge for agents_engine/model/DeepSeekClient.kt — DeepSeek Chat Completions adapter. Reuses the OpenAI-compatible /chat/completions mapping with provider identity `deepseek`, default base URL https://api.deepseek.com, OpenAI-format tools/SSE parsing, thinking disabled for tool-loop compatibility, and constrained decoding disabled because DeepSeek documents JSON object mode rather than response_format.json_schema. Call when the IDE LLM needs to reason about wiring the framework to DeepSeek.
---

# `agents_engine/model/DeepSeekClient.kt` — DeepSeek Chat Completions adapter

DeepSeek exposes an OpenAI-format `POST /chat/completions` API, so the adapter subclasses `OpenAiClient` and keeps the message, tool-call, usage, and SSE parsing mechanics aligned with the OpenAI-compatible wire shape.

## Construction

```kotlin
agent<X, Y>("...") {
    model {
        deepseek("deepseek-v4-flash")
        apiKey = System.getenv("DEEPSEEK_API_KEY")
        deepSeekBaseUrl = "https://api.deepseek.com"
        temperature = 0.7
        maxTokens = 4096
    }
}
```

## Provider Identity

Token usage and agent events report `provider = "deepseek"` instead of `openai`, and provider error envelopes surface as `DeepSeek returned an error: ...`.

## Wire Mapping

DeepSeek uses the OpenAI-compatible chat shape:

- `LlmMessage("system" | "user", text)` stays in the `messages` array.
- Assistant tool calls use `tool_calls` with stringified `function.arguments`.
- Tool results use `role: "tool"` plus `tool_call_id`.
- Tool definitions use `{"type":"function","function":{"name","description","parameters"}}`.
- Streaming uses data-only SSE with `data: [DONE]` and optional final usage.

## Thinking Mode

Requests include `{"thinking":{"type":"disabled"}}`. DeepSeek thinking mode can return `reasoning_content`; when an assistant turn performs a tool call, DeepSeek requires that `reasoning_content` to be replayed in later requests. The provider-neutral `LlmMessage` history does not carry reasoning content yet, so the adapter disables thinking mode to keep multi-turn tool loops compatible.

## Constrained Decoding

`supportsConstrainedDecoding()` returns `false`. DeepSeek documents `response_format: {"type":"json_object"}`, not OpenAI's `response_format: {"type":"json_schema", ...}`. The agentic loop therefore does not pass `@Generable` output schemas to this adapter.

## Related Files

- `OpenAiClient.kt` — shared OpenAI-compatible HTTP, parser, and streaming implementation.
- `ModelConfig.kt` — `model { deepseek(...) }` DSL and `deepSeekBaseUrl`.
- `AgenticLoop.kt` — lazy `DeepSeekClient` construction.
