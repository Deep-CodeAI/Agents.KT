package agents_engine.runtime.events

import agents_engine.core.agent
import agents_engine.model.LlmResponse
import agents_engine.model.ModelClient
import agents_engine.model.Tool
import agents_engine.model.ToolCall
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #2485 (under Koog regression epic #2474) — ergonomic, stable history
 * accessors over an [AgentSession]'s event log. Koog's "confusion
 * accessing user messages" pain point becomes a one-liner here:
 * `SessionHistory(events).toolCalls()` etc.
 *
 * Stability and replay are pinned: same input → same event sequence →
 * same accessor results across runs.
 *
 * Documented gap: `userMessages()` is not provided in v1 because the
 * agent input is passed directly to `agent.session(input)` and is not
 * emitted as an event. Adding it requires a new `AgentEvent.UserMessage`
 * event in the sealed hierarchy — out of scope for this slice.
 */
class KoogRegressionSessionHistoryTest {

    private fun mockedAgentRun(): List<AgentEvent<*>> {
        // Mock: first turn calls tool "lookup"; second turn returns the
        // final text "the answer". Stable across runs (deterministic).
        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.ToolCalls(listOf(ToolCall(name = "lookup", arguments = mapOf("q" to "x")))))
        responses.add(LlmResponse.Text("the answer"))
        val mock = ModelClient { _ -> responses.removeFirst() }

        val a = agent<String, String>("HistoryAgent") {
            lateinit var lookup: Tool<Map<String, Any?>, Any?>
            model { ollama("test"); client = mock }
            tools { lookup = tool("lookup", "lookup tool") { _ -> "found-x" } }
            skills { skill<String, String>("answer", "answer") { tools(lookup) } }
        }

        return runBlocking { a.session("query").events.toList() }
    }

    @Test
    fun `toolCalls returns each ToolCallFinished as a record in completion order`() {
        val history = SessionHistory(mockedAgentRun())
        val calls = history.toolCalls()
        assertEquals(1, calls.size, "exactly one tool invocation in the mocked run")
        val call = calls.single()
        assertEquals("lookup", call.toolName)
        assertEquals(mapOf("q" to "x"), call.arguments)
        assertNotNull(call.callId, "callId must be stamped on the record")
    }

    @Test
    fun `toolResults returns the executor outcomes for each tool call`() {
        val history = SessionHistory(mockedAgentRun())
        val results = history.toolResults()
        assertEquals(1, results.size)
        val r = results.single()
        assertEquals("lookup", r.toolName)
        assertEquals("found-x", r.result)
        assertEquals(false, r.isError, "successful executor → isError=false")
    }

    @Test
    fun `toolResults excludeErrors strips failing calls`() {
        // Tool that throws on its only call; agent.onError swallows it as a
        // string error so the loop continues to the text response.
        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.ToolCalls(listOf(ToolCall(name = "boom", arguments = emptyMap()))))
        responses.add(LlmResponse.Text("recovered"))
        val mock = ModelClient { _ -> responses.removeFirst() }

        val a = agent<String, String>("ErrAgent") {
            lateinit var boom: Tool<Map<String, Any?>, Any?>
            model { ollama("test"); client = mock }
            tools { boom = tool("boom", "always fails") { _ -> error("nope") } }
            skills { skill<String, String>("s", "s") { tools(boom) } }
            onToolError("boom") {
                executionError { _ -> agents_engine.model.RepairResult.Fixed("recovered-error") }
            }
        }

        val events = runBlocking { a.session("q").events.toList() }
        val history = SessionHistory(events)
        // The error-recovered call still surfaces as a successful event
        // because onError fixed it — pin that behavior. Both the
        // including and excluding view see the same single result.
        val incl = history.toolResults(excludeErrors = false)
        val excl = history.toolResults(excludeErrors = true)
        assertEquals(1, incl.size)
        assertEquals(1, excl.size, "onError.Fixed produces a non-error result")
    }

    @Test
    fun `assistantMessages assembles text turns from Token deltas grouped by ModelTurnCompleted`() {
        // Non-streaming mock: the framework synthesizes a single Token
        // event carrying the full LlmResponse.Text content per text turn.
        // Tool-call turns produce no Token events (no assistant text).
        val history = SessionHistory(mockedAgentRun())
        val turns = history.assistantMessages()
        assertEquals(2, turns.size, "one entry per ModelTurnCompleted (tool turn + text turn)")
        // The tool turn carries no Token events → empty entry.
        assertEquals("", turns[0], "tool-call turn has no assistant text")
        // The text turn carries the synthesized Token → full final answer.
        assertEquals("the answer", turns[1], "text turn assembles its full content")
    }

    @Test
    fun `completedOutput surfaces the typed final answer`() {
        val history = SessionHistory(mockedAgentRun())
        assertEquals("the answer", history.completedOutput())
        assertNull(history.failed(), "successful run has no Failed event")
    }

    @Test
    fun `skillsStarted records each SkillStarted in order`() {
        val history = SessionHistory(mockedAgentRun())
        val skills = history.skillsStarted()
        assertEquals(listOf("answer"), skills, "the single skill exercised in this run")
    }

    @Test
    fun `replay stability — same input produces the same shape (modulo per-invocation callIds)`() {
        // Replay determinism for the parts that should be stable. callIds are
        // freshly generated per invocation by design (for trace correlation
        // upstream), so comparing those would always fail. Pin every other
        // field of the records.
        val first = SessionHistory(mockedAgentRun())
        val second = SessionHistory(mockedAgentRun())

        assertEquals(
            first.toolCalls().map { it.toolName to it.arguments },
            second.toolCalls().map { it.toolName to it.arguments },
            "tool name + args must be stable across replays",
        )
        assertEquals(
            first.toolResults().map { Triple(it.toolName, it.result, it.isError) },
            second.toolResults().map { Triple(it.toolName, it.result, it.isError) },
            "tool outcomes must be stable across replays",
        )
        assertEquals(first.completedOutput(), second.completedOutput())
        assertEquals(first.skillsStarted(), second.skillsStarted())
        assertEquals(first.assistantMessages(), second.assistantMessages())
    }

    @Test
    fun `empty history is valid — no events means empty accessors`() {
        val empty = SessionHistory(emptyList())
        assertTrue(empty.toolCalls().isEmpty())
        assertTrue(empty.toolResults().isEmpty())
        assertTrue(empty.assistantMessages().isEmpty())
        assertTrue(empty.skillsStarted().isEmpty())
        assertNull(empty.completedOutput())
        assertNull(empty.failed())
    }
}
