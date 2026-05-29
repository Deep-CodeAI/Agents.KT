package agents_engine.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Tests for the neutral [CacheHint] / [CacheSegment] model (#2656).
 *
 * The hint is what the framework attaches to an `LlmMessage` so that adapters
 * can translate to vendor-specific markers. Provider-neutral by construction;
 * no `cache_control`-style fields appear here.
 */
class CacheHintTest {

    @Test
    fun `CacheHint defaults — null ttl uses provider default, breakpoint on`() {
        val h = CacheHint(segment = CacheSegment.SystemPrompt)
        assertEquals(CacheSegment.SystemPrompt, h.segment)
        assertNull(h.ttl, "null ttl = adapter chooses provider default (Anthropic ~5min etc.)")
        assertTrue(h.breakpoint, "default is to place an explicit breakpoint")
    }

    @Test
    fun `CacheSegment singletons compare by identity (no per-instance data)`() {
        assertEquals(CacheSegment.SystemPrompt as CacheSegment, CacheSegment.SystemPrompt)
        assertEquals(CacheSegment.ToolDefs as CacheSegment, CacheSegment.ToolDefs)
        assertEquals(CacheSegment.Conversation as CacheSegment, CacheSegment.Conversation)
        assertNotEquals(CacheSegment.SystemPrompt as CacheSegment, CacheSegment.ToolDefs as CacheSegment)
    }

    @Test
    fun `CacheSegment Custom carries an id`() {
        val a = CacheSegment.Custom("knowledge-base")
        val b = CacheSegment.Custom("knowledge-base")
        val c = CacheSegment.Custom("system-instructions")
        assertEquals(a, b, "same id = equal")
        assertNotEquals(a as CacheSegment, c as CacheSegment, "different id = not equal")
        assertEquals("knowledge-base", a.id)
    }

    @Test
    fun `CacheHint with explicit ttl and non-breakpoint mode`() {
        val h = CacheHint(
            segment = CacheSegment.Custom("docs"),
            ttl = 30.minutes,
            breakpoint = false,
        )
        assertEquals(CacheSegment.Custom("docs"), h.segment)
        assertEquals(30.minutes, h.ttl)
        assertEquals(
            false,
            h.breakpoint,
            "breakpoint=false signals 'cacheable but no explicit marker' — for automatic-prefix-caching providers",
        )
    }

    @Test
    fun `CacheHint equality is structural`() {
        val a = CacheHint(CacheSegment.SystemPrompt, 5.minutes)
        val b = CacheHint(CacheSegment.SystemPrompt, 5.minutes)
        val c = CacheHint(CacheSegment.SystemPrompt, 10.minutes)
        assertEquals(a, b)
        assertNotEquals(a, c)
    }
}
