---
description: Source-file knowledge for agents_engine/model/CacheConfig.kt — the vendor-neutral prompt-caching DSL slot. Five knobs (enabled, cacheSystemPrompt, cacheToolDefs, cacheConversation = None|Rolling, ttl) plus a customSegments list of CustomCacheSegment(id, content, ttl) declared via the cacheable(...) builder helper. Call when the IDE LLM needs to reason about prompt-caching configuration on an agent (#2656, part of #2655 epic).
---

# `agents_engine/model/CacheConfig.kt` — vendor-neutral caching DSL

The public, agent-controllable surface for prompt caching. The agent author declares *what* is cacheable in vendor-agnostic terms; adapters translate to each provider's mechanism (Anthropic `cache_control` breakpoints, OpenAI / DeepSeek automatic prefix caching, Gemini explicit handles, Ollama / vLLM engine APC). **No provider cache types appear here.**

## The knobs

| Field | Default | Purpose |
|---|---|---|
| `enabled` | `true` | Master switch. When `false`, no hints emit even if the other knobs are on. Use for measurement / A-B. |
| `cacheSystemPrompt` | `true` | Mark the system-prompt segment cacheable. Byte-stable for a fixed agent build. |
| `cacheToolDefs` | `true` | Mark the tool-definitions block cacheable. KSP-stable (#1703). |
| `cacheConversation` | `None` | `None` or `Rolling`. Rolling anchors a breakpoint at each turn end so the growing prefix keeps hitting; opt-in because rolling has per-vendor write cost (Anthropic +25% on cached-write tokens). |
| `ttl` | `null` | Desired cache TTL. Null = adapter uses provider default (Anthropic ~5 min). Adapters whose providers ignore TTL silently no-op. |
| `customSegments` | `emptyList()` | User-declared cacheable content blocks via the `cacheable(...)` helper. |

`cacheSystemPrompt` and `cacheToolDefs` are independent knobs but today combine into one wire behaviour because the agentic loop concatenates system prompt + tool descriptions into a single `"system"`-role message. Adapters with finer-grained markers may split internally.

## DSL

```kotlin
agent<X, Y>("...") {
    caching {
        enabled = true
        cacheSystemPrompt = true
        cacheToolDefs = true
        cacheConversation = CacheConversation.Rolling
        ttl = 5.minutes
        cacheable("rag-context", ttl = 30.minutes) { largeDoc }
        cacheable("rules") { "house rules…" }
    }
}
```

`cacheable(id, ttl) { content }` appends a `CustomCacheSegment(id, content, ttl)` to `customSegments`. The content lambda evaluates once at build time. The `id` doubles as a per-vendor routing key; **changing it busts the cache**.

## How the loop consumes it

`AgenticLoop.executeAgentic` reads `agent.cacheConfig` at message-assembly time:

1. The main system message is tagged with `CacheHint(SystemPrompt, ttl)` when `enabled && (cacheSystemPrompt || cacheToolDefs)`.
2. Each `customSegments` entry becomes its own `"system"`-role `LlmMessage` carrying `CacheHint(Custom(id), seg.ttl ?: ttl)`. Content emits regardless of `enabled`; only the hint drops when caching is off.
3. Under `cacheConversation = Rolling`, the assistant tool-call message added at each turn boundary carries `CacheHint(Conversation, ttl)`.

See [CacheHint.md](CacheHint.md) for the hint shape.

## Status

DSL surface ships under #2656; adapter consumption lands per-provider in #2658-#2662 (Anthropic / OpenAI / Gemini / DeepSeek / Ollama). Until those merge, the hints flow through unchanged — adapters ignore the new optional `LlmMessage.cacheHint` field, preserving the pre-#2656 wire shape exactly.
