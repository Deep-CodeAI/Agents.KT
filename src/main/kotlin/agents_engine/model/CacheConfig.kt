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
 * Conversation-history caching mode.
 *
 * The agentic loop resends the entire conversation prefix on every turn.
 * That prefix is mostly byte-identical (older turns never change) but grows
 * by one assistant + one user message each round. [Rolling] inserts a fresh
 * cache breakpoint at the end of every turn so the growing prefix continues
 * to hit. Default is [None] — rolling has a per-vendor write cost
 * (Anthropic charges 25% more for the cached-write tokens) and only pays
 * back on long loops; opt-in keeps short loops from paying the write tax
 * for cache hits they would never collect.
 */
enum class CacheConversation { None, Rolling }

/**
 * A user-declared cacheable content block — covers the "per-segment marking
 * for large custom context" requirement in the #2656 AC. Added via
 * `caching { cacheable(id, ttl) { ... } }`; surfaces in the prompt as its
 * own segment with [CacheSegment.Custom] so adapters can route it
 * independently of the system / tool-defs segments.
 *
 * @property id stable identifier; doubles as the per-vendor routing key.
 *   Changing it busts the cache.
 * @property content the content block to cache.
 * @property ttl per-segment TTL override. Null = fall back to
 *   [CacheConfig.ttl] or the provider default.
 */
data class CustomCacheSegment(
    val id: String,
    val content: String,
    val ttl: Duration? = null,
)

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

/** Mutable builder backing the `caching { }` DSL slot on `Agent`. */
class CacheBuilder {
    var enabled: Boolean = true
    var cacheSystemPrompt: Boolean = true
    var cacheToolDefs: Boolean = true
    var cacheConversation: CacheConversation = CacheConversation.None
    var ttl: Duration? = null

    private val customSegmentsList: MutableList<CustomCacheSegment> = mutableListOf()

    /**
     * Mark a custom content block as cacheable. Appended after the system
     * prompt as its own [CacheSegment.Custom] segment.
     *
     * @param id stable identifier — doubles as the per-vendor routing key.
     *   Changing it busts the cache.
     * @param ttl per-segment TTL; null = fall back to [CacheConfig.ttl] or
     *   the provider default.
     * @param content content lambda — evaluated once at build time.
     */
    fun cacheable(id: String, ttl: Duration? = null, content: () -> String) {
        customSegmentsList += CustomCacheSegment(id = id, content = content(), ttl = ttl)
    }

    internal fun build() = CacheConfig(
        enabled = enabled,
        cacheSystemPrompt = cacheSystemPrompt,
        cacheToolDefs = cacheToolDefs,
        cacheConversation = cacheConversation,
        ttl = ttl,
        customSegments = customSegmentsList.toList(),
    )
}
