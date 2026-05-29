package agents_engine.model

import agents_engine.core.agent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Tests for [CacheConfig] / [CacheBuilder] and the `caching { }` DSL slot
 * on [agents_engine.core.Agent] (#2656).
 *
 * Defaults are deliberately chosen to land cache hits on the byte-stable
 * parts of the prompt (system prompt + KSP-generated tool defs, #1703)
 * without enabling conversation-rolling implicitly — that one is opt-in
 * because it changes per-turn breakpoint placement and trades hit-rate
 * for write-overhead per vendor.
 */
class CacheConfigTest {

    @Test
    fun `default CacheConfig is enabled with system+tool defs cached, conversation off`() {
        val c = CacheConfig()
        assertTrue(c.enabled, "caching is on by default")
        assertTrue(c.cacheSystemPrompt, "system prompt cached by default (byte-stable)")
        assertTrue(c.cacheToolDefs, "tool defs cached by default (#1703 KSP-stable)")
        assertEquals(CacheConversation.None, c.cacheConversation, "conversation rolling off by default — opt-in")
        assertNull(c.ttl, "ttl null = let each adapter use its provider's default")
    }

    @Test
    fun `default CacheBuilder builds the default CacheConfig`() {
        assertEquals(CacheConfig(), CacheBuilder().build())
    }

    @Test
    fun `CacheBuilder respects field overrides`() {
        val c = CacheBuilder().apply {
            enabled = false
            cacheSystemPrompt = false
            cacheToolDefs = false
            cacheConversation = CacheConversation.Rolling
            ttl = 5.minutes
        }.build()
        assertEquals(
            CacheConfig(
                enabled = false,
                cacheSystemPrompt = false,
                cacheToolDefs = false,
                cacheConversation = CacheConversation.Rolling,
                ttl = 5.minutes,
            ),
            c,
        )
    }

    @Test
    fun `agent caching{} DSL block populates cacheConfig`() {
        val a = agent<String, String>("cached") {
            caching {
                cacheSystemPrompt = false
                cacheConversation = CacheConversation.Rolling
                ttl = 10.minutes
            }
            skills { skill<String, String>("s", "stub") { implementedBy { it } } }
        }
        assertEquals(false, a.cacheConfig.cacheSystemPrompt)
        assertEquals(true, a.cacheConfig.cacheToolDefs, "fields not overridden keep their default")
        assertEquals(CacheConversation.Rolling, a.cacheConfig.cacheConversation)
        assertEquals(10.minutes, a.cacheConfig.ttl)
    }

    @Test
    fun `agent without caching{} block has default CacheConfig`() {
        val a = agent<String, String>("plain") {
            skills { skill<String, String>("s", "stub") { implementedBy { it } } }
        }
        assertEquals(CacheConfig(), a.cacheConfig)
    }
}
