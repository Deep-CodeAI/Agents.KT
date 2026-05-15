package agents_engine.runtime.events

import agents_engine.core.agent
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

// #1736 — TDD red-first for the v0.5.0 streaming consumer surface.
//
// The narrowest meaningful test: an `implementedBy` skill on a typed
// agent, invoked via `agent.session(input)`, must produce ordered
// `SkillStarted → SkillCompleted → Completed` events and the deferred
// terminal must return the typed output. No LLM is involved — Token
// and ToolCall* events are defined in the hierarchy but NOT emitted
// in this step (step 3 will rewire the agentic loop to surface them).
//
// If the agentic-loop rewire later changes event semantics for
// `implementedBy` skills, this test fires. The contract is: zero
// Token / ToolCall events for non-agentic skills, by construction.

class AgentSessionBasicEventsTest {

    @Test
    fun `session emits SkillStarted then SkillCompleted then Completed for an implementedBy skill`() = runTest {
        val echoAgent = agent<String, String>("echo") {
            skills {
                skill<String, String>("uppercase", "Uppercases the input") {
                    implementedBy { it.uppercase() }
                }
            }
        }

        val session = echoAgent.session("hello")
        val events = session.events.toList()
        val output = session.await()

        assertEquals("HELLO", output, "session.await() must return the typed agent output")
        assertEquals(3, events.size, "expected 3 events for the implementedBy happy path; got: $events")

        val started = events[0]
        assertIs<AgentEvent.SkillStarted>(started, "first event must be SkillStarted; got: $started")
        assertEquals("echo", started.agentId)
        assertEquals("uppercase", started.skillName)

        val completed = events[1]
        assertIs<AgentEvent.SkillCompleted>(completed, "second event must be SkillCompleted; got: $completed")
        assertEquals("echo", completed.agentId)
        assertEquals("uppercase", completed.skillName)
        assertNull(completed.tokensUsed, "SkillCompleted.tokensUsed stays null until step 3 threads it through executeAgentic")

        val terminal = events[2]
        assertIs<AgentEvent.Completed<String>>(terminal, "third event must be Completed<String>; got: $terminal")
        assertEquals("echo", terminal.agentId)
        assertEquals("HELLO", terminal.output)
        assertNull(terminal.tokensUsed)
    }
}
