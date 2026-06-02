package agents_engine.model

import kotlin.time.Duration

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
