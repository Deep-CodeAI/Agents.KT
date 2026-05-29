package agents_engine.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Threads [CacheHint] onto [LlmMessage] as an optional, default-null field
 * (#2656). Backward-compatible: existing call sites and adapters keep
 * working — a missing hint means "no cache marker," which is the same
 * behaviour they had before.
 */
class LlmMessageCacheHintTest {

    @Test
    fun `LlmMessage default has no cacheHint — backward-compatible`() {
        val m = LlmMessage(role = "system", content = "you are helpful")
        assertNull(m.cacheHint, "no hint by default — pre-#2656 wire shape preserved")
    }

    @Test
    fun `LlmMessage carries an explicit cacheHint when supplied`() {
        val hint = CacheHint(segment = CacheSegment.SystemPrompt)
        val m = LlmMessage(role = "system", content = "you are helpful", cacheHint = hint)
        assertNotNull(m.cacheHint)
        assertEquals(CacheSegment.SystemPrompt, m.cacheHint!!.segment)
    }

    @Test
    fun `LlmMessage equality includes cacheHint`() {
        val a = LlmMessage("system", "x", cacheHint = CacheHint(CacheSegment.SystemPrompt))
        val b = LlmMessage("system", "x", cacheHint = CacheHint(CacheSegment.SystemPrompt))
        val c = LlmMessage("system", "x", cacheHint = CacheHint(CacheSegment.ToolDefs))
        val d = LlmMessage("system", "x") // no hint
        assertEquals(a, b)
        assertEquals(false, a == c, "different segment → not equal")
        assertEquals(false, a == d, "hint vs no hint → not equal")
    }
}
