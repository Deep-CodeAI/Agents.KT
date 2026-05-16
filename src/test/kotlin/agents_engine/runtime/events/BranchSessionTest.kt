package agents_engine.runtime.events

import agents_engine.composition.branch.branch
import agents_engine.composition.branch.session
import agents_engine.core.agent
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

// #1748 — agent.branch { ... } returns Branch<IN, OUT>. The session
// streams events from the source agent first, then from the routed
// agent (whichever one matched), terminated by a single Completed.

class BranchSessionTest {

    sealed interface Decision {
        data class Approved(val confidence: Double) : Decision
        data class Rejected(val reason: String) : Decision
    }

    @Test
    fun `branch session emits source agent's events then the routed agent's events`() = runTest {
        val classifier = agent<String, Decision>("classifier") {
            skills {
                skill<String, Decision>("classify", "Classifies the input") {
                    implementedBy { _ -> Decision.Approved(0.95) }
                }
            }
        }
        val approvedHandler = agent<Decision.Approved, String>("approved-handler") {
            skills {
                skill<Decision.Approved, String>("handle-approved", "Formats approved") {
                    implementedBy { "approved with ${it.confidence}" }
                }
            }
        }
        val rejectedHandler = agent<Decision.Rejected, String>("rejected-handler") {
            skills {
                skill<Decision.Rejected, String>("handle-rejected", "Formats rejected") {
                    implementedBy { "rejected: ${it.reason}" }
                }
            }
        }
        val branch = classifier.branch<String, Decision, String> {
            on<Decision.Approved>() then approvedHandler
            on<Decision.Rejected>() then rejectedHandler
        }

        val session = branch.session("anything")
        val events = session.events.toList()
        val output = session.await()

        assertEquals("approved with 0.95", output)

        // Classifier ran first.
        val classifierStarted = events.filterIsInstance<AgentEvent.SkillStarted>()
            .firstOrNull { it.agentId == "classifier" }
            ?: error("expected SkillStarted(classifier); got: $events")
        assertEquals("classify", classifierStarted.skillName)

        // Then the routed handler — only the approved branch ran.
        val handlerStarted = events.filterIsInstance<AgentEvent.SkillStarted>()
            .firstOrNull { it.agentId == "approved-handler" }
            ?: error("expected SkillStarted(approved-handler); got: $events")
        assertEquals("handle-approved", handlerStarted.skillName)

        // The rejected branch must NOT have fired — no events from rejected-handler.
        assertTrue(
            events.none { it is AgentEvent.SkillStarted && it.agentId == "rejected-handler" },
            "rejected-handler must not run when input was classified Approved; got: $events",
        )

        // Order: classifier brackets precede handler brackets.
        val classifierCompletedIdx = events.indexOfFirst { it is AgentEvent.SkillCompleted && it.agentId == "classifier" }
        val handlerStartedIdx = events.indexOfFirst { it is AgentEvent.SkillStarted && it.agentId == "approved-handler" }
        assertTrue(classifierCompletedIdx < handlerStartedIdx, "classifier.SkillCompleted must precede approved-handler.SkillStarted")

        // Terminal Completed carries the routed agent's name.
        val terminal = events.last()
        assertIs<AgentEvent.Completed<String>>(terminal)
        assertEquals("approved-handler", terminal.agentId, "Completed.agentId = name of the agent that produced the final output")
        assertEquals("approved with 0.95", terminal.output)
    }
}
