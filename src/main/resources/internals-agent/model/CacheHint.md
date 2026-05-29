---
description: Source-file knowledge for agents_engine/model/CacheHint.kt — the neutral hint model (segment + ttl + breakpoint) attached to LlmMessage by the agentic loop, plus the sealed CacheSegment interface (SystemPrompt / ToolDefs / Conversation / Custom(id)). Call when the IDE LLM needs to reason about how vendor-neutral cache hints flow through prompts (#2656).
---

# `agents_engine/model/CacheHint.kt` — neutral cache hint attached to messages

The internal hint model the agentic loop attaches to `LlmMessage`s so adapters can translate to provider-specific mechanisms. **No provider cache types here.** A hint a given adapter can't honour silently no-ops — caching is a latency / cost optimisation, never a correctness condition.

## Shape

```kotlin
data class CacheHint(
    val segment: CacheSegment,
    val ttl: Duration? = null,
    val breakpoint: Boolean = true,
)

sealed interface CacheSegment {
    data object SystemPrompt : CacheSegment      // system + tool-defs block
    data object ToolDefs : CacheSegment          // reserved — see below
    data object Conversation : CacheSegment      // rolling turn-end breakpoint
    data class  Custom(val id: String) : CacheSegment   // user-declared cacheable block
}
```

| Field | Meaning |
|---|---|
| `segment` | Which logical part of the prompt the hint covers. Adapters route vendor-specific decisions on this axis instead of guessing from message role/index. |
| `ttl` | Desired cache lifetime. Null = adapter uses its provider's default (Anthropic ~5 min). Adapters whose providers ignore explicit TTL silently no-op this. |
| `breakpoint` | `true` (default) → adapter should place an explicit cache marker here (Anthropic `cache_control`, Gemini handle boundary). `false` → segment tagged cacheable but no marker requested — for automatic-prefix-caching providers (OpenAI, DeepSeek, Ollama) where the tag feeds routing-key choice and observability but no on-wire marker is needed. |

## Where they appear

`LlmMessage.cacheHint: CacheHint?` carries the hint. Null = no marker (the default and pre-#2656 behaviour).

The agentic loop emits hints per `agent.cacheConfig` (see [CacheConfig.md](CacheConfig.md)):

- System message → `SystemPrompt` hint when `enabled && (cacheSystemPrompt || cacheToolDefs)`.
- Each `customSegments` entry → its own `"system"`-role `LlmMessage` with `Custom(id)` hint.
- Assistant tool-call turn-boundary message → `Conversation` hint when `cacheConversation = Rolling`.

## A note on `CacheSegment.ToolDefs`

Reserved. Today the system prompt and tool-defs block are concatenated into one `"system"`-role message, so they share the `SystemPrompt` hint. The `ToolDefs` tag is held for future use when the loop splits them (some providers — Anthropic — accept multi-block `system` content with per-block markers, which would let `cacheSystemPrompt` and `cacheToolDefs` flip independently on the wire).

## What adapters do with it

Adapter consumption is out of scope here and ships per-provider in #2658-#2662. The contract for all of them:

1. Provider supports caching → translate hint to its mechanism, place markers / handles / routing keys.
2. Provider doesn't → ignore the hint, behave as pre-#2656.

Either way the framework's job is done at hint-emit time; the no-op path is what keeps the DSL safe to ship before any adapter consumes it.
