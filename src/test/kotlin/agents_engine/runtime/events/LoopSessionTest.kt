package agents_engine.runtime.events

import agents_engine.composition.loop.loop
import agents_engine.composition.loop.session
import agents_engine.core.agent
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

// #1749 — Loop.session runs the wrapped agent (or pipeline) repeatedly,
// streaming bracket events per iteration with the same agentId.
// Terminal Completed carries the loop's final output.

class LoopSessionTest {

    @Test
    fun `loop session emits bracket events per iteration plus a terminal Completed`() = runTest {
        val doubler = agent<Int, Int>("doubler") {
            skills {
                skill<Int, Int>("double", "Doubles the input") {
                    implementedBy { it * 2 }
                }
            }
        }
        // 1 → 2 → 4 → 8 (terminate, since 8 >= 8).
        val loop = doubler.loop(maxIterations = 5) { if (it >= 8) null else it }

        val session = loop.session(1)
        val events = session.events.toList()
        val output = session.await()

        assertEquals(8, output, "loop output is 1 * 2 * 2 * 2 = 8 after three iterations")

        val starts = events.filterIsInstance<AgentEvent.SkillStarted>()
        val completedSkills = events.filterIsInstance<AgentEvent.SkillCompleted>()
        assertEquals(3, starts.size, "expected exactly 3 SkillStarted events (one per iteration); got: $starts")
        assertEquals(3, completedSkills.size, "expected exactly 3 SkillCompleted events; got: $completedSkills")

        // All bracket events share the wrapped agent's agentId.
        starts.forEach { assertEquals("doubler", it.agentId, "every iteration must carry the wrapped agent's name") }
        completedSkills.forEach { assertEquals("doubler", it.agentId) }

        // Order: each Started/Completed pair is contiguous, and iterations are sequential.
        // events[0] = Started, events[1] = Completed, events[2] = Started, events[3] = Completed, ...
        for (i in 0 until 3) {
            val started = events[i * 2]
            val completed = events[i * 2 + 1]
            assertIs<AgentEvent.SkillStarted>(started, "event ${i * 2} should be SkillStarted")
            assertIs<AgentEvent.SkillCompleted>(completed, "event ${i * 2 + 1} should be SkillCompleted")
        }

        // Terminal Completed.
        val terminal = events.last()
        assertIs<AgentEvent.Completed<Int>>(terminal)
        assertEquals("doubler", terminal.agentId, "Loop's terminal Completed uses the wrapped agent's name")
        assertEquals(8, terminal.output)
    }
}
