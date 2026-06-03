package agents_engine.core

import agents_engine.model.TokenUsage
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * #2793 — pins the observability listener slots + multi-subscriber `fire*` dispatch extracted out of
 * the Agent god class into [ListenerRegistry], including the swallow-and-log policy (via
 * [dispatchSafely]) so a throwing telemetry subscriber can never break dispatch to the others.
 */
class ListenerRegistryTest {

    @Test
    fun `token-usage subscribers fire in registration order and a throwing one is swallowed`() {
        val registry = ListenerRegistry()
        val seen = mutableListOf<String>()
        registry.addTokenUsageListener { seen += "a" }
        registry.addTokenUsageListener { error("boom") }
        registry.addTokenUsageListener { seen += "c" }
        assertEquals(3, registry.tokenUsageListenerCount, "all three are registered, including the thrower")
        registry.fireTokenUsage(TokenUsage(promptTokens = 1, completionTokens = 1, provider = "test"))
        assertEquals(listOf("a", "c"), seen, "a throwing subscriber must not stop later ones")
    }

    @Test
    fun `tokenUsageListenerCount reflects registrations`() {
        val registry = ListenerRegistry()
        assertEquals(0, registry.tokenUsageListenerCount)
        registry.addTokenUsageListener { }
        registry.addTokenUsageListener { }
        assertEquals(2, registry.tokenUsageListenerCount)
    }

    @Test
    fun `a single-slot listener holds the most recently registered block`() {
        val registry = ListenerRegistry()
        var captured: String? = null
        registry.skillChosenListener = { captured = it }
        registry.skillChosenListener?.invoke("router")
        assertEquals("router", captured)
    }
}
