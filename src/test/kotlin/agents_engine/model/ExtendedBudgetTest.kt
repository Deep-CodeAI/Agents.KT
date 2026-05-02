package agents_engine.model

import agents_engine.core.agent
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds

/**
 * Tests for #637 — extended budget controls beyond `maxTurns`:
 *   - maxToolCalls: hard cap on tool invocations across the whole loop
 *   - maxDuration: wall-clock cap from invocation start
 *   - perToolTimeout: per-tool wall-clock cap
 *
 * `BudgetExceededException.reason` distinguishes which limit triggered.
 */
class ExtendedBudgetTest {

    @Test
    fun `default BudgetConfig has all four caps with sensible values`() {
        val cfg = BudgetConfig()
        assertEquals(8, cfg.maxTurns)
        assertEquals(32, cfg.maxToolCalls)
        assertEquals(300_000L, cfg.maxDuration.inWholeMilliseconds)  // 5 minutes
        assertEquals(null, cfg.perToolTimeout)
    }

    @Test
    fun `maxToolCalls caps invocations across multiple turns`() {
        var executions = 0
        val responses = ArrayDeque<LlmResponse>()
        // Each turn emits 2 tool calls, so 4 tool calls happen in 2 turns
        repeat(10) {
            responses.add(LlmResponse.ToolCalls(listOf(
                ToolCall(name = "ping", arguments = emptyMap()),
                ToolCall(name = "ping", arguments = emptyMap()),
            )))
        }
        val mock = ModelClient { _ -> responses.removeFirst() }

        val a = agent<String, String>("loop") {
            model { ollama("llama3"); client = mock }
            tools { tool("ping", "") { _ -> executions++; "pong" } }
            skills { skill<String, String>("s", "stub") { tools("ping") } }
            budget { maxToolCalls = 3 }   // cap
        }

        try {
            a("input")
            fail("expected BudgetExceededException")
        } catch (e: BudgetExceededException) {
            assertEquals(BudgetReason.TOOL_CALLS, e.reason)
            assertTrue(executions <= 3, "tool must not run after the cap; ran $executions times")
        }
    }

    @Test
    fun `maxDuration enforces wall-clock bound`() {
        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.ToolCalls(listOf(ToolCall(name = "slow", arguments = emptyMap()))))
        responses.add(LlmResponse.ToolCalls(listOf(ToolCall(name = "slow", arguments = emptyMap()))))
        responses.add(LlmResponse.Text("done"))
        val mock = ModelClient { _ -> Thread.sleep(80); responses.removeFirst() }

        val a = agent<String, String>("slow") {
            model { ollama("llama3"); client = mock }
            tools { tool("slow", "") { _ -> Thread.sleep(80); "ok" } }
            skills { skill<String, String>("s", "stub") { tools("slow") } }
            budget { maxDuration = 100.milliseconds }
        }

        try {
            a("input")
            fail("expected BudgetExceededException")
        } catch (e: BudgetExceededException) {
            assertEquals(BudgetReason.DURATION, e.reason)
        }
    }

    @Test
    fun `perToolTimeout interrupts a hanging tool`() {
        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.ToolCalls(listOf(ToolCall(name = "hang", arguments = emptyMap()))))
        responses.add(LlmResponse.Text("done"))
        val mock = ModelClient { _ -> responses.removeFirst() }

        val a = agent<String, String>("hangy") {
            model { ollama("llama3"); client = mock }
            tools { tool("hang", "") { _ -> Thread.sleep(2_000); "ok" } }
            skills { skill<String, String>("s", "stub") { tools("hang") } }
            budget { perToolTimeout = 100.milliseconds }
        }

        try {
            a("input")
            fail("expected BudgetExceededException")
        } catch (e: BudgetExceededException) {
            assertEquals(BudgetReason.PER_TOOL_TIMEOUT, e.reason)
            assertTrue(e.message!!.contains("hang"), "message must name the tool: ${e.message}")
        }
    }

    @Test
    fun `maxTurns reason still works (regression)`() {
        val responses = ArrayDeque<LlmResponse>()
        repeat(20) {
            responses.add(LlmResponse.ToolCalls(listOf(ToolCall(name = "ping", arguments = emptyMap()))))
        }
        val mock = ModelClient { _ -> responses.removeFirst() }

        val a = agent<String, String>("turns") {
            model { ollama("llama3"); client = mock }
            tools { tool("ping", "") { _ -> "pong" } }
            skills { skill<String, String>("s", "stub") { tools("ping") } }
            budget { maxTurns = 3; maxToolCalls = 100 }   // turns wins first
        }

        try { a("input"); fail("expected throw") } catch (e: BudgetExceededException) {
            assertEquals(BudgetReason.TURNS, e.reason)
        }
    }

    @Test
    fun `perToolTimeout default null does not interfere (regression)`() {
        // A non-hanging tool with no timeout configured should run normally.
        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.ToolCalls(listOf(ToolCall(name = "fast", arguments = emptyMap()))))
        responses.add(LlmResponse.Text("done"))
        val mock = ModelClient { _ -> responses.removeFirst() }

        val a = agent<String, String>("fast") {
            model { ollama("llama3"); client = mock }
            tools { tool("fast", "") { _ -> "result" } }
            skills { skill<String, String>("s", "stub") { tools("fast") } }
        }
        // No throw; returns normally
        assertEquals("done", a("input"))
    }
}
