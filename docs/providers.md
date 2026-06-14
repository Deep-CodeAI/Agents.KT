[← Back to README](../README.md)

# Provider Capability Matrix

Single source of truth for what each shipped `ModelProvider` supports. `ModelProvider.entries` has **eight** values: `OLLAMA`, `ANTHROPIC`, `OPENAI`, `DEEPSEEK`, `KIMI`, `OPENROUTER`, `PERPLEXITY`, `GEMINI`. There are **five distinct wire shapes** — the four columns below (Anthropic / OpenAI / Ollama / DeepSeek) plus **`GEMINI`**, a from-scratch adapter documented in [§Gemini](#gemini-fifth-wire-shape) (its `contents`/`parts` shape is unlike the others, so it gets its own section rather than a column). `KIMI`, `OPENROUTER`, and `PERPLEXITY` are first-party providers that **extend the OpenAI adapter** (`model { kimi(...) }` / `model { openrouter(...) }` / `model { perplexity(...) }`), so they share the OpenAI column's behavior. Perplexity additionally accepts OpenAI's `response_format` json_schema, so its constrained decoding gate is left **on** (Kimi/DeepSeek leave it off).

For other deployments that ride on one of these wire shapes (vLLM, SGLang, …) see [caching.md → Under evaluation](caching.md#under-evaluation).

## Modality input

| Content type | Anthropic (Claude) | OpenAI | Ollama | DeepSeek |
|---|---|---|---|---|
| Image (`Content.Image` / `LlmMessage.images`) | ✅ all vision models (Haiku 4.5, Sonnet 4.6, Opus 4.7) | ✅ `gpt-4o`, `gpt-4o-mini`, `gpt-4-turbo`, o-series | ✅ model-dependent — works with `qwen3-vl:8b`, `llava`, `llama3.2-vision`; non-vision models silently ignore the field | ⚠️ inherits OpenAI wire shape; current DeepSeek models lack vision and ignore the field (shape-tested, no live call) |
| Document (`Content.Document`) | ⏳ deferred (#2470 slice c) | ⏳ deferred (#2470 slice c) | ⏳ deferred (#2470 slice c) | ⏳ deferred (#2470 slice c) |
| Audio / Video | ⏳ Stage 2 | ⏳ Stage 2 | ⏳ Stage 2 | ⏳ Stage 2 |

Document and Audio/Video `Content` variants exist today and flow through the audit pipeline and the agentic loop's tool-result placeholder rendering — they just aren't routed to provider input on the wire yet (this table is about provider *input* blocks). **Audio is nonetheless end-to-end** via the speech *tools* (#4501 — `transcribe_audio` / `speak`, with self-hosted `WhisperSttClient` / `QwenTtsClient`): the model transcribes/synthesizes through STT/TTS endpoints rather than the chat-message wire. See [multimodal.md](multimodal.md) for the closed mime hierarchy, the speech tools, and `BlobStore` design.

## Reasoning

| Surface | Anthropic | OpenAI | Ollama | DeepSeek |
|---|---|---|---|---|
| `reasoning(enabled, budgetTokens, effort)` | ✅ extended thinking; `budgetTokens` sets the budget; temperature forced to 1 | ✅ `reasoning_effort` from `effort`; reports `reasoningTokens` count only — Chat Completions returns no reasoning text | ✅ `think: true`; reasoning arrives in `message.thinking` | ✅ `reasoning_content` — use a reasoner model (e.g. `deepseek-reasoner`) |
| `AgentEvent.Reasoning` text on the stream | ✅ | ❌ (text not on the wire — count only) | ✅ | ✅ |
| `TokenUsage.reasoningTokens` | ✅ | ✅ | ✅ | ✅ |

## Prompt caching

| Mechanism | Anthropic | OpenAI | Ollama | DeepSeek |
|---|---|---|---|---|
| Wire-level control | ✅ explicit `cache_control` breakpoints (≤ 4 per request) | ⚪ automatic prefix caching (no wire control) | ⚪ engine-level KV-cache reuse (no wire control) | ⚪ inherits OpenAI shape; automatic disk caching upstream |
| `prompt_cache_key` routing hint | n/a | ✅ derived from agent identity + manifestHash prefix | n/a | ✅ same path as OpenAI |
| `TokenUsage.cachedInputTokens` (cache reads) | ✅ | ✅ | ❌ (engine KV cache; no wire surface) | ✅ |
| `TokenUsage.cacheWriteTokens` (premium-rate writes) | ✅ (25% surcharge) | ❌ (no write/read split on wire) | ❌ | ❌ |
| TTL control | ✅ default ephemeral or `"ttl":"1h"` when `ttl > 5.minutes` | n/a | n/a | n/a |

See [caching.md](caching.md) for the per-segment `caching { }` DSL and the prefix-stability guard (#2657).

## Tool calling

| Surface | Anthropic | OpenAI | Ollama | DeepSeek |
|---|---|---|---|---|
| Native tools field | ✅ `tools: [{name, description, input_schema}]` | ✅ `tools: [{type:"function", function:{name, description, parameters}}]` | ✅ same shape as OpenAI; auto-falls back to inline JSON tool-call format on models that reject the `tools` field (#706) | ✅ inherits OpenAI shape |
| `ToolChoice.Auto` | ✅ (default — field omitted) | ✅ (default — field omitted) | ✅ (default) | ✅ (default) |
| `ToolChoice.Required` | ✅ `{"type":"any"}` (Anthropic spelling) | ✅ `{"type":"required"}` | ⚠️ best-effort hint — no native `tool_choice`; one-shot JUL warning then treats as Auto | ✅ same as OpenAI |
| `ToolChoice.None` | ✅ `tools` array dropped from request | ✅ `{"type":"none"}` (and tools array dropped) | ✅ `tools` array dropped — genuinely enforceable on Ollama | ✅ same as OpenAI |
| `ToolChoice.Specific(name)` | ✅ `{"type":"tool","name":...}` | ✅ `{"type":"function","function":{"name":...}}` | ⚠️ best-effort hint | ✅ same as OpenAI |

## Constrained decoding (`@Generable` outputs)

| Provider | Wire shape | Status |
|---|---|---|
| Anthropic | Forced `structured_output` tool with `input_schema = <json schema>`; tool input converted back to final JSON text | ✅ |
| OpenAI | `response_format: { type: "json_schema", json_schema: { name, schema, strict: true } }` | ✅ |
| Ollama | Top-level `format: <json schema>` | ✅ |
| DeepSeek | Inherits OpenAI shape | ✅ |

All four `ModelClient` adapters report `supportsConstrainedDecoding() == true`; the agentic loop hands them the schema first and falls back to lenient deserialisation on the result.

## Streaming

| Surface | Anthropic | OpenAI | Ollama | DeepSeek |
|---|---|---|---|---|
| Native SSE / NDJSON streaming | ✅ SSE (indexed content blocks) | ✅ SSE (flat token deltas) | ✅ NDJSON | ✅ inherits OpenAI SSE |
| `LlmChunk.TextDelta` | ✅ | ✅ | ✅ | ✅ |
| `LlmChunk.ToolCall*` chunks | ✅ | ✅ | ✅ | ✅ |
| `LlmChunk.ReasoningDelta` | ✅ | ❌ (count only on `End`) | ✅ | ✅ |
| Token usage on `End` | ✅ `message_delta.usage` | ✅ via `stream_options.include_usage: true` | ✅ `done: true` NDJSON line | ✅ same as OpenAI |

## Web-grounded search tool (`perplexitySearch`, #3676 / #3677)

Distinct from the `perplexity(...)` **model** above: a `ToolDef` that lets an agent reasoning on its **own** model (Claude, OpenAI, Ollama, …) fetch live, cited facts from Perplexity's Sonar API. Register it on any agent:

```kotlin
val perplexityKey = java.nio.file.Files.readString(
    java.nio.file.Paths.get(".secrets", "perplexity-key"),
).trim()

agent<String, String>("researcher") {
    model { claude("claude-opus-4-7"); apiKey = anthropicKey }   // your own model
    tools { +perplexitySearchTool(perplexityKey) }               // grounded search as a tool
    skills { /* … */ }
}
```

- **Untrusted by construction.** The tool is `untrustedOutput = true`, so the agentic loop wraps every result in the `{"trusted":false, …}` envelope and the system prompt warns the model to treat it as data, not instructions (#642). Web search is the canonical prompt-injection vector — this contains it for free.
- **Citations as audit evidence.** The result renders the answer followed by a numbered source list parsed from `search_results[]` (title/url/snippet/date), falling back to `citations[]` URLs. Sources land in both the model context and the JSONL audit row.
- **Controls** via `perplexitySearchOptions { }` map to the documented request params: `model` (`sonar` / `sonar-pro` / `sonar-reasoning-pro` / `sonar-deep-research`), `mode` (`search_mode` web/academic/sec), `recency` (`search_recency_filter`), `allowDomains`/`denyDomains` (`search_domain_filter`, deny serialized with a `-` prefix), `contextSize` (`web_search_options.search_context_size`), `reasoningEffort`, and `structuredOutput(MyType::class)` (native `response_format` json_schema from a `@Generable` type).

```kotlin
tools {
    +perplexitySearchTool(
        perplexityKey,
        perplexitySearchOptions {
            model = "sonar-pro"
            mode = SearchMode.ACADEMIC
            recency = SearchRecency.WEEK
            allowDomains("arxiv.org", "nature.com")
            contextSize = SearchContextSize.HIGH
        },
    )
}
```

Credentials load from `.secrets/perplexity-key` / `PERPLEXITY_API_KEY` (the per-provider `.secrets/<provider>-key` convention). Verified end-to-end against `api.perplexity.ai`; live tests are tagged `live-cloud-api` (run in the default suite when a key is present, skip otherwise).

## Gemini (fifth wire shape)

`model { gemini("gemini-2.5-flash"); apiKey = ... }` — Google's Generative Language API (#1917). A
full from-scratch adapter, **not** OpenAI-compatible, so it stands apart from the four columns above:

| Surface | Gemini wire shape |
|---|---|
| Roles / messages | `contents:[{role:"user"\|"model", parts:[...]}]`; system → top-level `systemInstruction`; tool result → `{functionResponse:{name, response:{output:...}}}` paired by **function name** (no call id) |
| Tool calling | `tools:[{functionDeclarations:[{name, description, parametersJsonSchema}]}]`; `functionCall` parts in the response. `ToolChoice` → `toolConfig.functionCallingConfig.mode` (`AUTO`/`ANY`/`NONE`, `allowedFunctionNames` for `Specific`) |
| Constrained decoding | `generationConfig.responseMimeType="application/json"` + `responseJsonSchema` (tools must be empty); returns JSON text. `supportsConstrainedDecoding() == true` |
| Streaming | native SSE via `:streamGenerateContent?alt=sse`; `TextDelta` per text part, whole-`functionCall` → `ToolCall*`, `usageMetadata` → `End`. Non-2xx (e.g. 429) surfaces as `LlmProviderException`, not an empty stream |
| Reasoning | `thinkingConfig.includeThoughts` (+ optional `thinkingBudget`); response parts with `"thought":true` → `ReasoningDelta` / `LlmResponse.reasoning`; `thoughtsTokenCount` → `TokenUsage.reasoningTokens`. **Note:** gemini-2.5-* think by default and thinking tokens count against `maxOutputTokens` — give a generous budget |
| Vision | `inlineData:{mimeType, data:<base64>}` parts (`LlmMessage.images`) |
| Errors | top-level `{"error":{code, message, status}}` → `LlmProviderException` (same boundary contract as the others) |

Credentials load from `.secrets/gemini-key` / `GEMINI_API_KEY`. Live tests are tagged `live-cloud-api`
(run in the default suite when a key is present, skip otherwise; they also skip on free-tier
`RESOURCE_EXHAUSTED` rate limits). Verified end-to-end against `gemini-2.5-flash`.

## Updating this matrix

The four columns here track the OpenAI-family `ModelProvider.entries`; `GEMINI` is the fifth wire
shape in [§Gemini](#gemini-fifth-wire-shape). Adding a provider means:
1. Add the entry to `ModelProvider` enum + adapter implementation.
2. Add a column to **every** table in this file (or, for a genuinely new wire shape, its own section like Gemini's) and update the count word + entry list at the top.
3. Add caching + live integration tests under the relevant `live-*` tag.
4. Update [caching.md](caching.md) `Per-provider behavior` table at the same time.

This file is the canonical reference cross-linked from README, `docs/model-and-tools.md`, and `docs/multimodal.md`. Capability claims elsewhere should point here rather than restate.
