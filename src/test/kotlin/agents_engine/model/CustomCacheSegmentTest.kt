package agents_engine.model

import agents_engine.core.agent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Per-segment custom-content marking via `caching { cacheable(...) }`
 * (#2656 AC). Used to flag large retrieved documents or instruction sets
 * as cacheable, in addition to the byte-stable system prompt / tool-defs
 * defaults. Each segment ends up as its own [CacheHint] with
 * [CacheSegment.Custom] tag so adapters can route per-segment decisions.
 */
class CustomCacheSegmentTest {

    @Test
    fun `default CacheConfig has no custom segments`() {
        assertEquals(emptyList(), CacheConfig().customSegments)
    }

    @Test
    fun `cacheable() adds segments in declaration order, captures content lazily`() {
        var called = 0
        val c = CacheBuilder().apply {
            cacheable("docs", ttl = 1.hours) {
                called++
                "big retrieved doc"
            }
            cacheable("rules") {
                called++
                "house rules"
            }
        }.build()
        assertEquals(2, called, "content lambda invoked once per cacheable() call")
        assertEquals(
            listOf(
                CustomCacheSegment(id = "docs", content = "big retrieved doc", ttl = 1.hours),
                CustomCacheSegment(id = "rules", content = "house rules", ttl = null),
            ),
            c.customSegments,
        )
    }

    @Test
    fun `cacheable() segments survive through the agent caching{} block`() {
        val a = agent<String, String>("ctx") {
            caching {
                cacheable("knowledge", ttl = 30.minutes) { "domain knowledge" }
            }
            skills { skill<String, String>("s", "stub") { implementedBy { it } } }
        }
        assertEquals(1, a.cacheConfig.customSegments.size)
        val seg = a.cacheConfig.customSegments.single()
        assertEquals("knowledge", seg.id)
        assertEquals("domain knowledge", seg.content)
        assertEquals(30.minutes, seg.ttl)
    }

    @Test
    fun `CustomCacheSegment with no ttl uses provider default`() {
        val seg = CustomCacheSegment(id = "x", content = "y")
        assertNull(seg.ttl, "null ttl = adapter / config-level default")
    }
}
