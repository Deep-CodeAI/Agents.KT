package agents_engine.runtime.events

import agents_engine.core.PipelineEvent
import agents_engine.core.agent
import agents_engine.core.observe
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

class AgentRuntimeContextTest {

    @Test
    fun `observe events get a fresh request id per invoke and null manifest hash by default`() {
        val a = agent<String, String>("observed") {
            skills {
                skill<String, String>("echo", "echo") { implementedBy { it } }
            }
        }
        val events = mutableListOf<PipelineEvent>()
        a.observe { events += it }

        assertEquals("one", a("one"))
        assertEquals("two", a("two"))

        val chosen = events.filterIsInstance<PipelineEvent.SkillChosen>()
        assertEquals(2, chosen.size)
        val first = chosen[0]
        val second = chosen[1]

        UUID.fromString(first.requestId)
        UUID.fromString(second.requestId)
        assertNotEquals(first.requestId, second.requestId)
        assertNull(first.sessionId)
        assertNull(second.sessionId)
        assertNull(first.manifestHash)
        assertNull(second.manifestHash)
    }

    @Test
    fun `session events share request and session ids within one session but differ across sessions`() = runTest {
        val a = agent<String, String>("sessioned") {
            skills {
                skill<String, String>("echo", "echo") { implementedBy { it } }
            }
        }

        val firstEvents = a.session("one").events.toList()
        val secondEvents = a.session("two").events.toList()

        val firstRequestIds = firstEvents.map { it.requestId }.toSet()
        val firstSessionIds = firstEvents.map { it.sessionId }.toSet()
        val secondRequestIds = secondEvents.map { it.requestId }.toSet()
        val secondSessionIds = secondEvents.map { it.sessionId }.toSet()

        assertEquals(1, firstRequestIds.size, "one session should share requestId: $firstEvents")
        assertEquals(1, firstSessionIds.size, "one session should share sessionId: $firstEvents")
        assertEquals(1, secondRequestIds.size, "one session should share requestId: $secondEvents")
        assertEquals(1, secondSessionIds.size, "one session should share sessionId: $secondEvents")

        val firstRequestId = firstRequestIds.single()
        val firstSessionId = firstSessionIds.single()
        val secondRequestId = secondRequestIds.single()
        val secondSessionId = secondSessionIds.single()

        UUID.fromString(firstRequestId)
        UUID.fromString(requireNotNull(firstSessionId))
        UUID.fromString(secondRequestId)
        UUID.fromString(requireNotNull(secondSessionId))
        assertNotEquals(firstRequestId, secondRequestId)
        assertNotEquals(firstSessionId, secondSessionId)
        assertEquals(setOf(null), firstEvents.map { it.manifestHash }.toSet())
        assertEquals(setOf(null), secondEvents.map { it.manifestHash }.toSet())
        assertNotNull(firstEvents.filterIsInstance<AgentEvent.Completed<String>>().single().sessionId)
    }
}
