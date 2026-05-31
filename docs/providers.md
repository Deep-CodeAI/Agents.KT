[← Back to README](../README.md)

# Provider Capability Matrix

Single source of truth for what each shipped `ModelProvider` supports. `ModelProvider.entries` has **six** values: `OLLAMA`, `ANTHROPIC`, `OPENAI`, `DEEPSEEK`, `KIMI`, `OPENROUTER`. The four columns below are the four distinct **wire shapes** — the adapters with their own wiring, caching tests, and live integration coverage. `KIMI` and `OPENROUTER` are first-party providers that **extend the OpenAI adapter** (`model { kimi(...) }` / `model { openrouter(...) }`), so they share the OpenAI column's behavior.

For other deployments that ride on one of these wire shapes (vLLM, SGLang, …) see [caching.md → Under evaluation](caching.md#under-evaluation).

## Modality input

| Content type | Anthropic (Claude) | OpenAI | Ollama | DeepSeek |
|---|---|---|---|---|
| Image (`Content.Image` / `LlmMessage.images`) | ✅ all vision models (Haiku 4.5, Sonnet 4.6, Opus 4.7) | ✅ `gpt-4o`, `gpt-4o-mini`, `gpt-4-turbo`, o-series | ✅ model-dependent — works with `qwen3-vl:8b`, `llava`, `llama3.2-vision`; non-vision models silently ignore the field | ⚠️ inherits OpenAI wire shape; current DeepSeek models lack vision and ignore the field (shape-tested, no live call) |
| Document (`Content.Document`) | ⏳ deferred (#2470 slice c) | ⏳ deferred (#2470 slice c) | ⏳ deferred (#2470 slice c) | ⏳ deferred (#2470 slice c) |
| Audio / Video | ⏳ Stage 2 | ⏳ Stage 2 | ⏳ Stage 2 | ⏳ Stage 2 |

Document and Audio/Video `Content` variants exist today and flow through the audit pipeline and the agentic loop's tool-result placeholder rendering — they just aren't routed to provider input on the wire yet. See [multimodal.md](multimodal.md) for the closed mime hierarchy and `BlobStore` design.

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

## Updating this matrix

The four columns here track `ModelProvider.entries`. Adding a fifth provider means:
1. Add the entry to `ModelProvider` enum + adapter implementation.
2. Add a column to **every** table in this file.
3. Add caching + live integration tests under the relevant `live-*` tag.
4. Update [caching.md](caching.md) `Per-provider behavior` table at the same time.

This file is the canonical reference cross-linked from README, `docs/model-and-tools.md`, and `docs/multimodal.md`. Capability claims elsewhere should point here rather than restate.
