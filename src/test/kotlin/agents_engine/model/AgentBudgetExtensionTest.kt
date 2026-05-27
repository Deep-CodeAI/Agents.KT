package agents_engine.model

import agents_engine.core.agent
import org.junit.jupiter.api.assertThrows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #2412 — `onBudgetExceeded` lets a handler raise a budget cap and continue
 * instead of throwing. Models the field case: "Agent 'ActorsAgent' exceeded
 * tool-call budget of 32. But we need to continue."
 */
class AgentBudgetExtensionTest {

    /** A stub model that issues `toolTurns` single-tool turns, then a final answer. */
    private fun scriptedModel(toolTurns: Int): ModelClient {
        val responses = ArrayDeque<LlmResponse>()
        repeat(toolTurns) { responses.add(LlmResponse.ToolCalls(listOf(ToolCall("step", emptyMap())))) }
        responses.add(LlmResponse.Text("done"))
        return ModelClient { _ -> responses.removeFirst() }
    }

    @Test
    fun `without a handler, exceeding maxToolCalls throws (baseline)`() {
        val a = agent<String, String>("ActorsAgent") {
            lateinit var step: Tool<Map<String, Any?>, Any?>
            model { ollama("test"); client = scriptedModel(toolTurns = 6) }
            budget { maxToolCalls = 3; maxTurns = 50; maxConsecutiveSameTool = 100 }
            tools { step = tool("step", "") { _ -> "ok" } }
            skills { skill<String, String>("s", "s") { tools(step) } }
        }

        val ex = assertThrows<BudgetExceededException> { a("go") }
        assertEquals(BudgetReason.TOOL_CALLS, ex.reason)
        assertTrue("tool-call budget of 3" in (ex.message ?: ""), ex.message)
    }

    @Test
    fun `onBudgetExceeded Extend raises the cap and the loop continues to completion`() {
        // The use case: start at maxToolCalls=2, but the work needs 5 tool calls.
        // Each time the cap is hit, grant 2 more and keep going.
        val toolRuns = intArrayOf(0)
        val extensions = mutableListOf<Pair<BudgetReason, Int>>()

        val a = agent<String, String>("ActorsAgent") {
            lateinit var step: Tool<Map<String, Any?>, Any?>
            model { ollama("test"); client = scriptedModel(toolTurns = 5) }
            budget { maxToolCalls = 2; maxTurns = 50; maxConsecutiveSameTool = 100 }
            tools { step = tool("step", "") { _ -> toolRuns[0]++; "ok" } }
            skills { skill<String, String>("s", "s") { tools(step) } }
            onBudgetExceeded { reason, currentLimit ->
                extensions += reason to currentLimit
                if (reason == BudgetReason.TOOL_CALLS) BudgetDecision.Extend(currentLimit + 2)
                else BudgetDecision.Stop
            }
        }

        val result = a("go")

        assertEquals("done", result, "the loop should continue past the cap and finish")
        assertEquals(5, toolRuns[0], "all 5 tool calls ran — well past the original cap of 2")
        // Hook fired at the cap each time it was hit: 2 → 4 → 6.
        assertEquals(listOf(BudgetReason.TOOL_CALLS to 2, BudgetReason.TOOL_CALLS to 4), extensions)
    }

    @Test
    fun `onBudgetExceeded Stop throws like the default`() {
        val a = agent<String, String>("ActorsAgent") {
            lateinit var step: Tool<Map<String, Any?>, Any?>
            model { ollama("test"); client = scriptedModel(toolTurns = 6) }
            budget { maxToolCalls = 3; maxTurns = 50; maxConsecutiveSameTool = 100 }
            tools { step = tool("step", "") { _ -> "ok" } }
            skills { skill<String, String>("s", "s") { tools(step) } }
            onBudgetExceeded { _, _ -> BudgetDecision.Stop }
        }

        val ex = assertThrows<BudgetExceededException> { a("go") }
        assertEquals(BudgetReason.TOOL_CALLS, ex.reason)
    }

    @Test
    fun `an Extend that does not exceed the current limit still stops`() {
        // Guard: a no-op / smaller extension can't be used to spin forever.
        val a = agent<String, String>("ActorsAgent") {
            lateinit var step: Tool<Map<String, Any?>, Any?>
            model { ollama("test"); client = scriptedModel(toolTurns = 6) }
            budget { maxToolCalls = 3; maxTurns = 50; maxConsecutiveSameTool = 100 }
            tools { step = tool("step", "") { _ -> "ok" } }
            skills { skill<String, String>("s", "s") { tools(step) } }
            onBudgetExceeded { _, currentLimit -> BudgetDecision.Extend(currentLimit) } // not greater → ignored
        }

        assertThrows<BudgetExceededException> { a("go") }
    }
}
