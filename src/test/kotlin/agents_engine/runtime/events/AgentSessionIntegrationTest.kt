package agents_engine.runtime.events

import agents_engine.core.agent
import agents_engine.model.LlmResponse
import agents_engine.model.ModelClient
import agents_engine.model.TokenUsage
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

// #1737 — integration coverage for the v0.5.0 session surface beyond the
// happy implementedBy path. These pin contracts that step 3 will need to
// preserve when the agentic loop is rewired onto a FlowCollector.

class AgentSessionIntegrationTest {

    @Test
    fun `failure path — Failed terminates events and the same exception rethrows from await`() = runTest {
        val boom = IllegalStateException("boom")
        val failingAgent = agent<String, String>("fails") {
            skills {
                skill<String, String>("explode", "Throws unconditionally") {
                    implementedBy { throw boom }
                }
            }
        }

        val session = failingAgent.session("anything")
        val events = session.events.toList()

        // Terminal event must be Failed — carries the original exception, not a wrapped one.
        assertTrue(events.isNotEmpty(), "expected at least one event before terminal Failed")
        val terminal = events.last()
        assertIs<AgentEvent.Failed>(terminal, "last event must be Failed; got: $terminal")
        assertEquals("fails", terminal.agentId)
        assertSame(boom, terminal.cause, "Failed.cause must be the original exception, not a wrapper")

        // No Completed event must appear — Failed and Completed are mutually exclusive per the premortem.
        assertTrue(events.none { it is AgentEvent.Completed<*> }, "Completed must NOT appear on the failure path")

        // session.await() rethrows an IllegalStateException with the same message.
        // Kotlin coroutines' CompletableDeferred copies the cause with a recovered
        // stack trace before rethrowing, so identity equality doesn't hold here —
        // AgentEvent.Failed.cause carries the original instance (identity-checked
        // above), and await() preserves type + message.
        val thrown = assertFailsWith<IllegalStateException> { session.await() }
        assertEquals(boom.message, thrown.message, "await() must rethrow with the original message")
    }

    @Test
    fun `concurrent sessions — two parallel invocations on the same agent don't share skill-name state`() = runTest {
        val echoAgent = agent<String, String>("echo") {
            skills {
                skill<String, String>("uppercase", "Uppercases the input") {
                    implementedBy { it.uppercase() }
                }
            }
        }

        // Launch two sessions in parallel. The closure-captured skill-name
        // holder is allocated per session.launch{}; if it were shared
        // (e.g., a global var), one session's events could carry the
        // other's skill name (still "uppercase" here — but the test would
        // catch any data-race-induced corruption like a null skill name).
        val (eventsA, outputA, eventsB, outputB) = coroutineScope {
            val sessionA = echoAgent.session("alpha")
            val sessionB = echoAgent.session("bravo")
            val a = async { sessionA.events.toList() }
            val b = async { sessionB.events.toList() }
            val outA = sessionA.await()
            val outB = sessionB.await()
            Quad(a.await(), outA, b.await(), outB)
        }

        assertEquals("ALPHA", outputA)
        assertEquals("BRAVO", outputB)

        for ((label, events) in listOf("A" to eventsA, "B" to eventsB)) {
            assertEquals(3, events.size, "session $label: expected 3 events; got: $events")
            val started = events[0]; assertIs<AgentEvent.SkillStarted>(started)
            assertEquals("uppercase", started.skillName, "session $label: skill name must not be corrupted by the other session")
            val completed = events[1]; assertIs<AgentEvent.SkillCompleted>(completed)
            assertEquals("uppercase", completed.skillName, "session $label: skill name on SkillCompleted")
            assertIs<AgentEvent.Completed<String>>(events[2])
        }
    }

    @Test
    fun `agentic-stub bracketing — SkillStarted SkillCompleted Completed wrap the loop, no Token or ToolCall events yet`() = runTest {
        // Stub model: completes the agentic loop in one turn.
        val usage = TokenUsage(promptTokens = 7, completionTokens = 4)
        val stub = ModelClient { _ -> LlmResponse.Text("done", usage) }

        val agenticAgent = agent<String, String>("agentic") {
            prompt("Test stub agent.")
            model { ollama("llama3"); client = stub }
            skills {
                skill<String, String>("respond", "Echoes back via the model") { tools() }
            }
        }

        val session = agenticAgent.session("kick")
        val events = session.events.toList()
        val output = session.await()

        assertEquals("done", output, "agentic skill output must equal the stub text")
        // Step 2 contract: only SkillStarted / SkillCompleted / Completed surface for agentic skills.
        // When step 3 rewires executeAgentic onto a FlowCollector, this assertion will need to
        // relax — at that point this test pins the new contract instead.
        assertTrue(
            events.none { it is AgentEvent.Token || it is AgentEvent.ToolCallStarted ||
                it is AgentEvent.ToolCallArgumentsDelta || it is AgentEvent.ToolCallFinished },
            "step 2 must not yet emit Token / ToolCall* events for agentic skills; got: $events",
        )
        assertEquals(3, events.size, "expected exactly [SkillStarted, SkillCompleted, Completed]; got: $events")
        val started = events[0]; assertIs<AgentEvent.SkillStarted>(started); assertEquals("respond", started.skillName)
        val completed = events[1]; assertIs<AgentEvent.SkillCompleted>(completed); assertEquals("respond", completed.skillName)
        val terminal = events[2]; assertIs<AgentEvent.Completed<String>>(terminal); assertEquals("done", terminal.output)
    }

    // Tiny generic 4-tuple — assertable via destructuring in the concurrent test.
    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
}
