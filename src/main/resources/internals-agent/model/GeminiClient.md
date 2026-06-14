---
description: Source-file knowledge for agents_engine/model/GeminiClient.kt — Google Gemini (Generative Language API) adapter (#1917). Full from-scratch adapter (NOT OpenAI-compatible). LlmMessage→Gemini contents/parts wire mapping (user/model roles, systemInstruction, functionDeclarations/functionCall, functionResponse paired by name, parametersJsonSchema/responseJsonSchema, toolConfig.functionCallingConfig, inlineData vision, thinkingConfig reasoning), native SSE via :streamGenerateContent?alt=sse, boundary errors via LlmProviderException, open sendChat/sendChatStream seams. Call when the IDE LLM needs to reason about wiring the framework to Gemini.
---

# `agents_engine/model/GeminiClient.kt` — Google Gemini adapter (#1917)

The eighth shipped `ModelClient`, alongside Ollama / Claude / OpenAI / DeepSeek / Kimi / OpenRouter / Perplexity. Gemini is **not** OpenAI-compatible, so this is a full from-scratch adapter (shaped like `ClaudeClient`, not a thin OpenAI subclass). Wraps `POST {baseUrl}/{apiVersion}/models/{model}:generateContent` (default `https://generativelanguage.googleapis.com` / `v1beta`). Auth is the `x-goog-api-key` header.

## Wire mapping (`LlmMessage` → Gemini request)

| `LlmMessage` | Gemini |
|---|---|
| `role="system"` | top-level `systemInstruction.parts[].text` (all system messages joined with `\n\n`) |
| `role="user"`, text | `{role:"user", parts:[{text}]}` |
| `role="user"`, `images` | adds `{inlineData:{mimeType, data:<base64>}}` parts |
| `role="assistant"`, text | `{role:"model", parts:[{text}]}` |
| `role="assistant"`, `toolCalls` | `{role:"model", parts:[{functionCall:{name, args}}...]}` |
| `role="tool"` | `{role:"user", parts:[{functionResponse:{name, response:{output:<text>}}}]}` |

**Key difference from Claude/OpenAI:** Gemini has **no tool-call id**. `functionResponse` is paired to its `functionCall` by **function name, in order** — the adapter keeps an `ArrayDeque<String>` of pending function names emitted by assistant turns and pops FIFO for each `tool` message (mirrors Claude's `pendingToolUseIds`, but keyed by name). Roles are `user`/`model` only; there is no `assistant`/`system` role on the wire.

## Tools & structured output

- Tool defs → `tools:[{functionDeclarations:[{name, description, parametersJsonSchema}]}]`. `parametersJsonSchema` accepts standard JSON Schema (what `argsType.jsonSchema()` emits), so no OpenAPI-subset sanitization is needed. No-arg tools (schema == `OPEN_EMPTY_OBJECT_SCHEMA_JSON`) omit the schema field.
- `ToolChoice` → `toolConfig.functionCallingConfig.mode`: `Auto`→omitted, `Required`→`ANY`, `None`→`NONE` (or tools dropped), `Specific`→`ANY` + `allowedFunctionNames:[name]`.
- Constrained decoding (`supportsConstrainedDecoding()` = true): a `JsonSchema` is honored only when `tools` is empty → `generationConfig.responseMimeType="application/json"` + `responseJsonSchema`. The JSON text is returned as `LlmResponse.Text` for the normal `@Generable` parser (#1949).

## Streaming & reasoning

- Native SSE via `:streamGenerateContent?alt=sse` (the `sendChatStream` seam). Each `data:` frame is a full `GenerateContentResponse` delta: text parts append (`TextDelta`), `functionCall` parts arrive **whole** (Gemini does not stream arg fragments) → `ToolCallStarted`+`ArgumentsDelta`(full)+`ToolCallFinished` with a synthesized `gemini_tool_<uuid>` id, `usageMetadata` on later chunks → terminal `End`.
- Reasoning: `reasoning.enabled` → `generationConfig.thinkingConfig.includeThoughts` (+ `thinkingBudget` when `budgetTokens` set). Response parts with `"thought":true` are collected into `LlmResponse.reasoning` (and `ReasoningDelta` in the stream), kept separate from the answer text. `usageMetadata.thoughtsTokenCount` → `TokenUsage.reasoningTokens`.

## Response parsing & errors

- `parseResponse` reads `candidates[0].content.parts[]`; `functionCall` parts → `LlmResponse.ToolCalls`, else text parts (excluding `thought`) → `LlmResponse.Text`. Token usage from `usageMetadata` (`promptTokenCount`/`candidatesTokenCount`/`cachedContentTokenCount`/`thoughtsTokenCount`), `provider="gemini"`.
- A top-level `{"error":{"code","message","status"}}` envelope (4xx/5xx) throws `LlmProviderException` — same boundary contract as the other adapters (#702), so the raw envelope never flows into `transformOutput`.

## Seams & wiring

- `internal open fun sendChat(body, endpoint)` and `sendChatStream(body)` are the test seams (overridden by `GeminiClientTest.StubClient` / streaming stubs).
- Wired through `ModelProvider.GEMINI`, `ModelConfig.geminiBaseUrl`, `ModelBuilder.gemini(modelName)`, and `ModelClientFactory.defaultClientFor`. `semconvProviderName(GEMINI) = "gemini"`.

## Related files

- `ClaudeClient.kt` — the closest structural sibling (from-scratch adapter, SSE, structured-output).
- `ModelClient.kt` — the interface + default `chatStream` wrapper.
- `ModelClientFactory.kt` / `ModelBuilder.kt` / `ModelConfig.kt` — provider dispatch + DSL wiring.
