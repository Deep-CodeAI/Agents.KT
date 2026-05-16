package agents_engine.runtime.events

import agents_engine.composition.parallel.div
import agents_engine.composition.parallel.session
import agents_engine.core.agent
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

// #1750 — Parallel session runs all agents concurrently; their events
// flow into a SHARED Flow and interleave by arrival order. Each event
// carries its own agentId so consumers can demultiplex.

class ParallelSessionTest {

    @Test
    fun `parallel session emits events from all agents with correct agentIds and a terminal list-output Completed`() = runTest {
        val upper = agent<String, String>("upper") {
            skills {
                skill<String, String>("uppercase", "Uppercases") {
                    implementedBy { it.uppercase() }
                }
            }
        }
        val lengthLabel = agent<String, String>("length-label") {
            skills {
                skill<String, String>("label-length", "Labels length") {
                    implementedBy { "len=${it.length}" }
                }
            }
        }
        val parallel = upper / lengthLabel

        val session = parallel.session("hello")
        val events = session.events.toList()
        val output = session.await()

        // Both outputs in branch order.
        assertEquals(listOf("HELLO", "len=5"), output)

        // Both agents' brackets appear, each with the right agentId/skillName.
        val starts = events.filterIsInstance<AgentEvent.SkillStarted>()
        val completedSkills = events.filterIsInstance<AgentEvent.SkillCompleted>()
        assertEquals(setOf("upper", "length-label"), starts.map { it.agentId }.toSet(),
            "every branch should emit SkillStarted; got: $starts")
        assertEquals(setOf("upper", "length-label"), completedSkills.map { it.agentId }.toSet())

        // No cross-contamination: each branch's events carry its own skillName.
        starts.first { it.agentId == "upper" }.let { assertEquals("uppercase", it.skillName) }
        starts.first { it.agentId == "length-label" }.let { assertEquals("label-length", it.skillName) }

        // Terminal Completed carries the list output under the "parallel" agentId.
        val terminal = events.last()
        assertIs<AgentEvent.Completed<List<String>>>(terminal, "last event must be Completed<List<String>>; got: $terminal")
        assertEquals("parallel", terminal.agentId, "Parallel has no single output agent; terminal Completed.agentId is the literal 'parallel'")
        assertEquals(listOf("HELLO", "len=5"), terminal.output)

        // Both branches' SkillCompleted MUST appear BEFORE the terminal Completed.
        val terminalIdx = events.indexOf(terminal)
        val completedBeforeTerminal = events.subList(0, terminalIdx).filterIsInstance<AgentEvent.SkillCompleted>()
        assertEquals(2, completedBeforeTerminal.size, "both branches must complete before the terminal; got events: $events")
        assertTrue(completedBeforeTerminal.map { it.agentId }.toSet() == setOf("upper", "length-label"))
    }
}
