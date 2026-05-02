package agents_engine.model

import agents_engine.core.agent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Tests for the default `maxTurns` value (issue #632).
 *
 * The bug: default was `Int.MAX_VALUE` — runaway loops can burn unlimited
 * tokens / make unlimited side-effecting tool calls.
 * The fix: default lowered to 8 (most well-designed loops complete in 3–6).
 */
class BudgetDefaultTest {

    @Test
    fun `default BudgetConfig has maxTurns equal to 8`() {
        assertEquals(8, BudgetConfig().maxTurns)
    }

    @Test
    fun `default BudgetBuilder builds maxTurns equal to 8`() {
        assertEquals(8, BudgetBuilder().build().maxTurns)
    }

    @Test
    fun `agent without explicit budget caps at 8 turns when model never converges`() {
        val responses = ArrayDeque<LlmResponse>()
        repeat(20) {
            responses.add(LlmResponse.ToolCalls(listOf(ToolCall(name = "ping", arguments = emptyMap()))))
        }
        val mock = ModelClient { _ -> if (responses.isEmpty()) LlmResponse.Text("done") else responses.removeFirst() }

        val a = agent<String, String>("loopy") {
            model { ollama("llama3"); client = mock }
            tools { tool("ping", "") { _ -> "pong" } }
            skills { skill<String, String>("s", "stub") { tools("ping") } }
            // No explicit budget — relies on default
        }

        try {
            a("input")
            fail("expected BudgetExceededException at turn 8")
        } catch (e: BudgetExceededException) {
            assertTrue(e.message!!.contains("8"), "error must reference the budget cap: ${e.message}")
        }
    }

    @Test
    fun `explicit budget override works for power users who want longer loops`() {
        val responses = ArrayDeque<LlmResponse>()
        repeat(15) {
            responses.add(LlmResponse.ToolCalls(listOf(ToolCall(name = "ping", arguments = emptyMap()))))
        }
        responses.add(LlmResponse.Text("finally"))
        val mock = ModelClient { _ -> responses.removeFirst() }

        val a = agent<String, String>("patient") {
            model { ollama("llama3"); client = mock }
            tools { tool("ping", "") { _ -> "pong" } }
            skills { skill<String, String>("s", "stub") { tools("ping") } }
            budget { maxTurns = 100 }   // override
        }

        // Should not throw — 100 > 16 turns required
        assertEquals("finally", a("input"))
    }
}
