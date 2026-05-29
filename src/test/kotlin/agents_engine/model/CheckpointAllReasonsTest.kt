package agents_engine.model

import agents_engine.core.SessionSnapshot
import agents_engine.core.agent
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.milliseconds
import org.junit.jupiter.api.assertThrows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * #2764 — `BudgetDecision.Checkpoint` was wired only for `BudgetReason
 * .TOOL_CALLS` (per #2749). #2750 broadened `Extend` to the four other
 * cumulative reasons (TURNS / DURATION / TOKENS / CONSECUTIVE_TOOL) but
 * Checkpoint stayed asymmetric. This suite pins the closed asymmetry: a
 * handler returning `Checkpoint` at every cumulative-cap site now captures
 * a `SessionSnapshot`, fires `onTurnCheckpoint`, and throws
 * `BudgetCheckpointException` carrying the same snapshot.
 *
 * Negative coverage: Checkpoint without `onTurnCheckpoint` falls back to
 * plain `BudgetExceededException` — same Stop semantics as TOOL_CALLS.
 */
class CheckpointAllReasonsTest {

    // -------- TURNS --------

    @Test
    fun `TURNS Checkpoint captures snapshot and throws BudgetCheckpointException`() {
        val responses = ArrayDeque<LlmResponse>()
        repeat(8) { responses.add(LlmResponse.ToolCalls(listOf(ToolCall("step", emptyMap())))) }
        val mock = ModelClient { _ -> responses.removeFirst() }

        val captured = mutableListOf<SessionSnapshot>()
        val a = agent<String, String>("a") {
            lateinit var step: Tool<Map<String, Any?>, Any?>
            model { ollama("t"); client = mock }
            budget { maxTurns = 2; maxToolCalls = 100; maxConsecutiveSameTool = 100 }
            tools { step = tool("step", "") { _ -> "ok" } }
            skills { skill<String, String>("s", "") { tools(step) } }
            onBudgetExceeded { reason, _ ->
                if (reason == BudgetReason.TURNS) BudgetDecision.Checkpoint else BudgetDecision.Stop
            }
        }

        val ex = assertThrows<BudgetCheckpointException> {
            runBlocking {
                a.invokeSuspendResuming(
                    input = "go",
                    onTurnCheckpoint = { snap -> captured += snap },
                )
            }
        }
        assertEquals(BudgetReason.TURNS, ex.reason, "exception names TURNS as the breach reason")
        assertNotNull(ex.snapshot, "exception carries the snapshot")
        assertTrue(captured.isNotEmpty(), "onTurnCheckpoint must have fired with the snapshot")
        assertEquals(ex.snapshot, captured.last(), "exception.snapshot == latest hook delivery")
    }

    @Test
    fun `TURNS Checkpoint without onTurnCheckpoint falls back to BudgetExceededException`() {
        val responses = ArrayDeque<LlmResponse>()
        repeat(8) { responses.add(LlmResponse.ToolCalls(listOf(ToolCall("step", emptyMap())))) }
        val mock = ModelClient { _ -> responses.removeFirst() }

        val a = agent<String, String>("a") {
            lateinit var step: Tool<Map<String, Any?>, Any?>
            model { ollama("t"); client = mock }
            budget { maxTurns = 2; maxToolCalls = 100; maxConsecutiveSameTool = 100 }
            tools { step = tool("step", "") { _ -> "ok" } }
            skills { skill<String, String>("s", "") { tools(step) } }
            onBudgetExceeded { _, _ -> BudgetDecision.Checkpoint }
        }

        val ex = assertThrows<BudgetExceededException> { a("go") }
        assertEquals(BudgetReason.TURNS, ex.reason)
        // The thrown exception is the plain class, not the checkpoint subclass.
        assertTrue(
            ex !is BudgetCheckpointException,
            "fallback must be the plain exception when no hook is registered",
        )
    }

    // -------- TOKENS --------

    @Test
    fun `TOKENS Checkpoint captures snapshot and throws BudgetCheckpointException`() {
        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.ToolCalls(
            listOf(ToolCall("step", emptyMap())),
            tokenUsage = TokenUsage(promptTokens = 100, completionTokens = 100, provider = "x", model = "y"),
        ))
        val mock = ModelClient { _ -> responses.removeFirst() }

        val captured = mutableListOf<SessionSnapshot>()
        val a = agent<String, String>("a") {
            lateinit var step: Tool<Map<String, Any?>, Any?>
            model { ollama("t"); client = mock }
            budget { maxTokens = 50; maxTurns = 50; maxToolCalls = 100; maxConsecutiveSameTool = 100 }
            tools { step = tool("step", "") { _ -> "ok" } }
            skills { skill<String, String>("s", "") { tools(step) } }
            onBudgetExceeded { _, _ -> BudgetDecision.Checkpoint }
        }

        val ex = assertThrows<BudgetCheckpointException> {
            runBlocking {
                a.invokeSuspendResuming(
                    input = "go",
                    onTurnCheckpoint = { snap -> captured += snap },
                )
            }
        }
        assertEquals(BudgetReason.TOKENS, ex.reason)
        assertNotNull(ex.snapshot)
        assertTrue(captured.isNotEmpty())
    }

    // -------- CONSECUTIVE_TOOL --------

    @Test
    fun `CONSECUTIVE_TOOL Checkpoint captures snapshot and throws BudgetCheckpointException`() {
        val responses = ArrayDeque<LlmResponse>()
        repeat(5) { responses.add(LlmResponse.ToolCalls(listOf(ToolCall("step", emptyMap())))) }
        val mock = ModelClient { _ -> responses.removeFirst() }

        val captured = mutableListOf<SessionSnapshot>()
        val a = agent<String, String>("a") {
            lateinit var step: Tool<Map<String, Any?>, Any?>
            model { ollama("t"); client = mock }
            budget { maxConsecutiveSameTool = 2; maxToolCalls = 100; maxTurns = 50 }
            tools { step = tool("step", "") { _ -> "ok" } }
            skills { skill<String, String>("s", "") { tools(step) } }
            onBudgetExceeded { _, _ -> BudgetDecision.Checkpoint }
        }

        val ex = assertThrows<BudgetCheckpointException> {
            runBlocking {
                a.invokeSuspendResuming(
                    input = "go",
                    onTurnCheckpoint = { snap -> captured += snap },
                )
            }
        }
        assertEquals(BudgetReason.CONSECUTIVE_TOOL, ex.reason)
        assertNotNull(ex.snapshot)
        assertTrue(captured.isNotEmpty())
    }

    // -------- DURATION --------

    @Test
    fun `DURATION Checkpoint captures snapshot and throws BudgetCheckpointException`() {
        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.ToolCalls(listOf(ToolCall("step", emptyMap()))))
        responses.add(LlmResponse.Text("done"))
        val mock = ModelClient { _ -> responses.removeFirst() }

        val captured = mutableListOf<SessionSnapshot>()
        val a = agent<String, String>("a") {
            lateinit var step: Tool<Map<String, Any?>, Any?>
            model { ollama("t"); client = mock }
            // Tight wall-clock so the second iteration trips the cap.
            budget { maxDuration = 1.milliseconds; maxToolCalls = 100; maxTurns = 50; maxConsecutiveSameTool = 100 }
            tools { step = tool("step", "") { _ -> Thread.sleep(50); "ok" } }
            skills { skill<String, String>("s", "") { tools(step) } }
            onBudgetExceeded { _, _ -> BudgetDecision.Checkpoint }
        }

        val ex = assertThrows<BudgetCheckpointException> {
            runBlocking {
                a.invokeSuspendResuming(
                    input = "go",
                    onTurnCheckpoint = { snap -> captured += snap },
                )
            }
        }
        assertEquals(BudgetReason.DURATION, ex.reason)
        assertNotNull(ex.snapshot)
        assertTrue(captured.isNotEmpty())
    }

    // -------- Resume composition pin --------

    @Test
    fun `TURNS Checkpoint snapshot resumes with raised budget (no replay tax)`() {
        // Run A: hit TURNS cap, capture via Checkpoint.
        val responsesA = ArrayDeque<LlmResponse>()
        repeat(5) { responsesA.add(LlmResponse.ToolCalls(listOf(ToolCall("step", emptyMap())))) }
        val mockA = ModelClient { _ -> responsesA.removeFirst() }

        val runsA = intArrayOf(0)
        val agentA = agent<String, String>("Resumable") {
            lateinit var step: Tool<Map<String, Any?>, Any?>
            model { ollama("t"); client = mockA }
            budget { maxTurns = 2; maxToolCalls = 100; maxConsecutiveSameTool = 100 }
            tools { step = tool("step", "") { _ -> runsA[0]++; "ok" } }
            skills { skill<String, String>("s", "") { tools(step) } }
            onBudgetExceeded { _, _ -> BudgetDecision.Checkpoint }
        }

        val captured = mutableListOf<SessionSnapshot>()
        val ex = assertThrows<BudgetCheckpointException> {
            runBlocking {
                agentA.invokeSuspendResuming(
                    input = "go",
                    onTurnCheckpoint = { snap -> captured += snap },
                )
            }
        }
        val seed = ex.snapshot
        assertEquals(BudgetReason.TURNS, ex.reason)
        assertTrue(runsA[0] >= 2, "A ran at least 2 tool calls before the cap")

        // Run B: fresh agent, raised maxTurns, resumes from the snapshot.
        val responsesB = ArrayDeque<LlmResponse>()
        responsesB.add(LlmResponse.ToolCalls(listOf(ToolCall("step", emptyMap()))))
        responsesB.add(LlmResponse.Text("done"))
        val mockB = ModelClient { _ -> responsesB.removeFirst() }

        val runsB = intArrayOf(0)
        val agentB = agent<String, String>("Resumable") {
            lateinit var step: Tool<Map<String, Any?>, Any?>
            model { ollama("t"); client = mockB }
            budget { maxTurns = 10; maxToolCalls = 100; maxConsecutiveSameTool = 100 }
            tools { step = tool("step", "") { _ -> runsB[0]++; "ok" } }
            skills { skill<String, String>("s", "") { tools(step) } }
        }

        val out = runBlocking { agentB.invokeSuspendResuming("go", resumeFrom = seed) }
        assertEquals("done", out)
        // B ran only the tool calls AFTER the resume point. The number depends on
        // what was in the snapshot (it captures BEFORE the breach), but it must
        // not replay A's prior calls.
        assertTrue(runsB[0] <= 2, "B did not replay A's prior tool calls (ran ${runsB[0]})")
    }
}
