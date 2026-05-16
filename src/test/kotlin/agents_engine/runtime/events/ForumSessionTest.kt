package agents_engine.runtime.events

import agents_engine.composition.forum.session
import agents_engine.composition.forum.times
import agents_engine.core.agent
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

// #1751 — Forum session streams events from all participants
// concurrently, then from the captain. Terminal Completed has the
// captain's agentId.

class ForumSessionTest {

    @Test
    fun `forum session emits participants' events then captain's events with captain as terminal agentId`() = runTest {
        val analyst = agent<String, String>("analyst") {
            skills {
                skill<String, String>("analyze", "Analyzes") {
                    implementedBy { "analysis: $it" }
                }
            }
        }
        val critic = agent<String, String>("critic") {
            skills {
                skill<String, String>("critique", "Critiques") {
                    implementedBy { "critique: $it" }
                }
            }
        }
        val captain = agent<String, String>("captain") {
            skills {
                skill<String, String>("verdict", "Final verdict") {
                    implementedBy { "verdict: $it" }
                }
            }
        }
        val forum = analyst * critic * captain

        val session = forum.session("topic")
        val events = session.events.toList()
        val output = session.await()

        assertEquals("verdict: topic", output)

        // Both participants' SkillStarted/SkillCompleted appear.
        val starts = events.filterIsInstance<AgentEvent.SkillStarted>()
        assertEquals(
            setOf("analyst", "critic", "captain"),
            starts.map { it.agentId }.toSet(),
            "every forum agent must emit SkillStarted; got: $starts",
        )

        // Captain's bracket comes AFTER both participants completed.
        val captainStartIdx = events.indexOfFirst { it is AgentEvent.SkillStarted && it.agentId == "captain" }
        val analystCompletedIdx = events.indexOfFirst { it is AgentEvent.SkillCompleted && it.agentId == "analyst" }
        val criticCompletedIdx = events.indexOfFirst { it is AgentEvent.SkillCompleted && it.agentId == "critic" }
        assertTrue(
            analystCompletedIdx < captainStartIdx,
            "analyst.SkillCompleted must precede captain.SkillStarted; got events: $events",
        )
        assertTrue(
            criticCompletedIdx < captainStartIdx,
            "critic.SkillCompleted must precede captain.SkillStarted; got events: $events",
        )

        // Terminal Completed.
        val terminal = events.last()
        assertIs<AgentEvent.Completed<String>>(terminal)
        assertEquals("captain", terminal.agentId, "Forum's terminal Completed uses the captain's name")
        assertEquals("verdict: topic", terminal.output)
    }
}
