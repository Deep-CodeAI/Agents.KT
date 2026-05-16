package agents_engine.runtime.events

import agents_engine.core.agent
import agents_engine.model.LlmResponse
import agents_engine.model.ModelClient
import agents_engine.model.ToolCall
import agents_engine.runtime.absorb
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

// #1752 — when an agent absorbs a sibling, the sibling becomes a TOOL
// on the captain. Under captain.session(input), the sibling's INNER
// events must also stream through to the captain's session — not just
// the captain's own ToolCallStarted/Finished bracket.

class SwarmSessionTest {

    @Test
    fun `absorbed sibling's inner events stream into the captain's session events`() = runTest {
        // Sibling: simple implementedBy agent. Its SkillStarted/SkillCompleted
        // must appear in the captain's session when the captain's LLM invokes it.
        val helper = agent<String, String>("helper") {
            skills {
                skill<String, String>("answer", "Answers questions") {
                    implementedBy { "answer for: $it" }
                }
            }
        }

        // Captain: stub ModelClient that issues exactly one tool call
        // to "helper", then a final text turn.
        val turn1 = LlmResponse.ToolCalls(
            listOf(
                ToolCall(
                    name = "helper",
                    arguments = mapOf("query" to "the question"),
                    rawArguments = """{"query":"the question"}""",
                    callId = "call-helper-1",
                ),
            ),
        )
        val turn2 = LlmResponse.Text("Final captain answer.")
        val responses = ArrayDeque<LlmResponse>().apply { add(turn1); add(turn2) }
        val stub = ModelClient { _ -> responses.removeFirst() }

        val captain = agent<String, String>("captain") {
            prompt("Captain that calls helpers.")
            model { ollama("llama3"); client = stub }
            skills {
                skill<String, String>("orchestrate", "Calls helpers as needed") { tools() }
            }
        }
        captain.absorb(helper)

        val session = captain.session("question")
        val events = session.events.toList()
        val output = session.await()

        assertEquals("Final captain answer.", output)

        // Captain's tool-call bracket appears with the explicit callId.
        val toolStarted = events.filterIsInstance<AgentEvent.ToolCallStarted>().single()
        assertEquals("captain", toolStarted.agentId)
        assertEquals("helper", toolStarted.toolName)
        assertEquals("call-helper-1", toolStarted.callId)

        val toolFinished = events.filterIsInstance<AgentEvent.ToolCallFinished>().single()
        assertEquals("call-helper-1", toolFinished.callId)
        assertEquals("answer for: the question", toolFinished.result?.toString())
        assertEquals(false, toolFinished.isError)

        // The sibling's INNER bracket events appear with helper's agentId.
        val helperStarted = events.filterIsInstance<AgentEvent.SkillStarted>()
            .firstOrNull { it.agentId == "helper" }
            ?: error("expected SkillStarted(helper) — sibling's inner events must stream into the captain's session; got: $events")
        assertEquals("answer", helperStarted.skillName)
        val helperCompleted = events.filterIsInstance<AgentEvent.SkillCompleted>()
            .firstOrNull { it.agentId == "helper" }
            ?: error("expected SkillCompleted(helper); got: $events")
        assertEquals("answer", helperCompleted.skillName)

        // Order: ToolCallStarted < helper.SkillStarted < helper.SkillCompleted < ToolCallFinished
        val toolStartedIdx = events.indexOf(toolStarted)
        val helperStartedIdx = events.indexOf(helperStarted)
        val helperCompletedIdx = events.indexOf(helperCompleted)
        val toolFinishedIdx = events.indexOf(toolFinished)
        assertTrue(toolStartedIdx < helperStartedIdx,
            "captain.ToolCallStarted must precede helper.SkillStarted; got: $events")
        assertTrue(helperCompletedIdx < toolFinishedIdx,
            "helper.SkillCompleted must precede captain.ToolCallFinished; got: $events")

        // Terminal Completed has captain's agentId.
        val terminal = events.last()
        assertIs<AgentEvent.Completed<String>>(terminal)
        assertEquals("captain", terminal.agentId)
    }
}
