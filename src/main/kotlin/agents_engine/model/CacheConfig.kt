package agents_engine.model

import kotlin.time.Duration

/**
 * `agents_engine/model/CacheConfig.kt` — the vendor-neutral prompt-caching
 * control surface (#2656, part of the #2655 epic). The agent author declares
 * *what* is cacheable in provider-agnostic terms; adapters translate the
 * neutral hints into each vendor's mechanism (Anthropic `cache_control`
 * breakpoints, OpenAI automatic prefix caching, Gemini explicit handles,
 * DeepSeek automatic, Ollama / vLLM engine prefix caching).
 *
 * No provider cache types ever appear in this public API. Hints a given
 * provider cannot honour degrade to a no-op — caching is a latency / cost
 * optimisation, never a correctness condition.
 *
 * See `src/main/resources/internals-agent/model/CacheConfig.md` for the
 * adjunct surfaced to IDE-side LLM tools via `agents-kt-internals`.
 */

/**
 * Vendor-neutral prompt-caching configuration (#2656). Defaults assume the
 * system prompt and the KSP-generated tool definitions block (#1703) are
 * byte-stable across turns — they cache by default. Conversation rolling
 * is opt-in (see [CacheConversation]).
 *
 * @property enabled master switch. When false, no hints are emitted even
 *   if the other knobs are on. Off here is identical in behaviour to a
 *   provider with no caching support — useful for measurement / A-B.
 * @property cacheSystemPrompt mark the system prompt segment as cacheable.
 *   Byte-stable for a given agent build, so default-on is safe.
 * @property cacheToolDefs mark the tool-definitions block as cacheable.
 *   The KSP-generated block is deterministic (#1703), so default-on is safe.
 * @property cacheConversation conversation-history caching mode. Default
 *   [CacheConversation.None]; switch to [CacheConversation.Rolling] for
 *   long loops where re-feeding a growing prefix dominates cost.
 * @property ttl desired cache TTL. Null = let each adapter use its
 *   provider's default (e.g. Anthropic ~5 min). Adapters whose providers
 *   ignore explicit TTL silently no-op this field.
 * @property customSegments user-declared cacheable content blocks (large
 *   retrieved documents, instruction sets) marked via the `cacheable(...)`
 *   helper. Empty by default.
 */
data class CacheConfig(
    val enabled: Boolean = true,
    val cacheSystemPrompt: Boolean = true,
    val cacheToolDefs: Boolean = true,
    val cacheConversation: CacheConversation = CacheConversation.None,
    val ttl: Duration? = null,
    val customSegments: List<CustomCacheSegment> = emptyList(),
)
