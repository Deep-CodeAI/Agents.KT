[← Back to README](../README.md)

## Prompt Caching

Vendor-neutral, agent-controllable prompt caching across all built-in providers. The agent author declares *what* is cacheable; adapters translate to each vendor's mechanism — Anthropic explicit `cache_control` breakpoints, OpenAI / DeepSeek automatic prefix caching, Ollama engine-level KV-cache reuse. No provider cache types appear in the public API. (See [Under evaluation](#under-evaluation) below for OpenAI-compatible upstreams like Kimi / OpenRouter and engine-level backends like vLLM / SGLang — they inherit the OpenAI wire shape but ship as fourth-party deployments, not first-party adapters in `ModelProvider`.)

The win: an agentic tool-calling loop resends a large identical prefix (system + tool defs + history) every turn. Caching turns that prefix into ~10% read cost (Anthropic) or zero-latency reuse (engine-level). For multi-step agents, this is the single biggest lever on per-run cost and latency.

### The DSL

```kotlin
val agent = agent<String, String>("ResearchBot") {
    model { claude("claude-opus-4-7"); apiKey = key }
    caching {
        enabled = true
        cacheSystemPrompt = true               // default on
        cacheToolDefs = true                   // default on
        cacheConversation = CacheConversation.Rolling
        ttl = 1.hours                          // Anthropic supports 5m (default) or 1h
        cacheable("style-guide", ttl = 1.hours) {
            "Always cite sources …"            // a custom retrieved doc
        }
    }
    // … skills, tools, etc.
}
```

`enabled = false` (the master switch) emits no hints, so caching is a pure opt-in. The other knobs control which segments carry cache hints when enabled.

### Per-provider behavior

| Provider | Mechanism | What we do |
|---|---|---|
| **Anthropic** (Claude) | Explicit `cache_control` breakpoints; up to 4 per request; TTL 5 min (default) or 1 h | Emit `cache_control:{type:"ephemeral"}` on system block, last tool def, rolling conversation breakpoints, and each custom segment. TTL ≤ 5 min → default form; > 5 min → `"ttl":"1h"`. Coalesce at 4. |
| **OpenAI** | Automatic prefix caching above ~1024 tokens; `prompt_cache_key` for routing | Emit a `prompt_cache_key` derived from the agent identity (+ manifestHash prefix when present) so same-shape requests land on the same cache shard. Cached-input tokens surface on `TokenUsage`. |
| **DeepSeek** | Automatic disk-based caching | Inherits the OpenAI-compatible request shape; no extra wiring needed. Cached-input tokens surface on `TokenUsage`. |
| **Ollama** | Engine-level KV-cache reuse (no wire-level control) | Hints degrade to no-op. Prefix stability (see below) is what makes the engine cache hit. |

The four rows above match `ModelProvider.entries` (Ollama / Anthropic / OpenAI / DeepSeek) — the only providers wired as first-party adapters today. Fourth-party deployments that ride on top of one of these wire shapes are documented under [Under evaluation](#under-evaluation) below.

### `CacheHint` model

Internally, the agentic loop attaches a `CacheHint` to each cacheable `LlmMessage`:

```kotlin
data class CacheHint(
    val segment: CacheSegment,     // SystemPrompt | ToolDefs | Conversation | Custom(id)
    val ttl: Duration? = null,
    val breakpoint: Boolean = true,
)
```

Adapters consume hints; ones a provider can't honor degrade to a no-op. Caching is a cost/latency optimization, never a correctness condition.

### Cache observability

Token accounting surfaces cache reads and writes:

```kotlin
agent.onTokenUsage { usage ->
    println("prompt=${usage.promptTokens} " +
            "cached=${usage.cachedInputTokens ?: 0} " +
            "cacheWrite=${usage.cacheWriteTokens ?: 0} " +
            "hitRate=${(usage.cacheHitRate ?: 0.0) * 100}%")
}
```

- `cachedInputTokens` — cache **reads** (Anthropic / OpenAI / DeepSeek).
- `cacheWriteTokens` — Anthropic's premium-billed cache **writes** (25% surcharge); null elsewhere.
- `cacheHitRate` — derived: `cachedInputTokens / promptTokens` when both available, else null.

The cumulative `TokenUsage` on `SkillCompleted` / `Completed` events sums both sides across all turns.

### Prefix-stability guard (#2657)

The framework hashes each cache-hinted segment and compares against the previous invocation of the same `Agent`. When the hash changes, the vendor cache silently misses — so the guard emits a `WARNING` log naming the segment:

```
Cacheable segment [SystemPrompt] for agent "ResearchBot" changed between
invocations (prior=…, current=…). Vendor cache will MISS until the prefix
is stable again. Check for timestamps, UUIDs, request ids, or non-
deterministic tool/section ordering in the segment content.
```

A first-sighting pattern probe also fires on:
- Unix millisecond timestamps (`\b\d{13}\b`)
- ISO-8601 datetimes (`\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}`)
- UUIDs (`xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx`)

These are the silent killers — `System.currentTimeMillis()` interpolated into a system prompt, a per-call `randomUUID()`, an unstable tool ordering. The guard catches them on the FIRST invocation so you don't pay for a single non-cached run before noticing.

### Common cache-busters (and how to fix them)

| Anti-pattern | Why it kills caching | Fix |
|---|---|---|
| `"You are an agent at ${LocalDateTime.now()}"` | Timestamp in cached segment changes every call | Move the timestamp into the user turn or a non-cached system block |
| `"Request ${UUID.randomUUID()}"` | Per-call UUID | Drop it, or use a stable identifier (request id can live in metadata, not in the prompt) |
| Tools registered in different orders across calls | Reordered tool defs produce a different byte stream | Use a stable tool registration order — `mapOf(...)` is unordered; `linkedMapOf(...)` or a sorted list is stable |
| Locale-dependent number/date formatting | `%.2f` on `Double` varies with `Locale.getDefault()` | Use `Locale.ROOT` for any formatting that goes into a cached segment |

### What's not in this slice

- Gemini explicit cached-content handles — no Gemini adapter exists in this codebase yet (tracked alongside the Gemini provider work).
- A cache-cost calculator (per-provider rates × tokens). Token counts are surfaced; deployers price them.
- A pluggable hit/miss event surface beyond `onTokenUsage`. Token usage events fire per turn already; richer observability lands when there's a concrete consumer ask.

### Under evaluation

These backends are **not** first-party `ModelProvider` adapters — `ModelProvider.entries` is `{ OLLAMA, ANTHROPIC, OPENAI, DEEPSEEK }`. Consumers who point the OpenAI adapter at one of these endpoints (via `openAiBaseUrl`) get OpenAI-compatible behavior, but the caching characteristics differ:

| Backend | Wire-shape compatibility | Caching note |
|---|---|---|
| **Kimi** (Moonshot) | OpenAI-compatible | Inherits OpenAI shape; `prompt_cache_key` not currently passed (Moonshot routes upstream itself). |
| **OpenRouter** | OpenAI-compatible | Inherits OpenAI shape; OpenRouter's own routing layer handles per-target cache semantics. |
| **vLLM APC** / **SGLang RadixAttention** | OpenAI-compatible endpoints | Engine-level KV-cache reuse; wire hints degrade to no-op. Prefix stability is what makes the engine cache hit. |

These are documented for completeness — a first-party `ModelProvider` entry (with its own adapter wiring, caching tests, and live integration coverage) would land in a separate ticket and bump the enum.
