package agents_engine.model

import kotlin.time.Duration

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
