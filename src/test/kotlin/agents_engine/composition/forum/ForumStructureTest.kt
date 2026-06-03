package agents_engine.composition.forum

import agents_engine.core.agent
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * #2802 — pins the `participants` / `captain` accessors exposed when the Forum deliberation core was
 * unified (the non-streaming [Forum.invokeSuspend] and the streaming `session` now both read these
 * instead of re-deriving `agents.dropLast(1)` / `agents.last()`).
 */
class ForumStructureTest {

    private fun stub(name: String) = agent<String, String>(name) {
        skills { skill<String, String>("s", "") { implementedBy { it } } }
    }

    @Test
    fun `captain is the last registered agent and participants are the rest, in order`() {
        val a = stub("alpha")
        val b = stub("beta")
        val cap = stub("captain")
        val forum = forum<String, String> { participant(a); participant(b); captain(cap) }

        assertEquals("captain", forum.captain.name)
        assertEquals(listOf("alpha", "beta"), forum.participants.map { it.name })
    }

    @Test
    fun `a single-agent forum has that agent as captain and no participants`() {
        val only = stub("solo")
        val forum = forum<String, String> { captain(only) }

        assertEquals("solo", forum.captain.name)
        assertEquals(emptyList(), forum.participants.map { it.name })
    }
}
