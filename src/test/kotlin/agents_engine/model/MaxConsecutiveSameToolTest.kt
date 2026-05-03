package agents_engine.model

import agents_engine.core.agent
import org.junit.jupiter.api.assertThrows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Tests for #969 — maxConsecutiveSameTool catches the LLM-retry-loop pattern
// where the same tool gets called over and over until maxToolCalls runs out.
class MaxConsecutiveSameToolTest {

    @Test
    fun `BudgetConfig default maxConsecutiveSameTool is null`() {
        assertNull(BudgetConfig().maxConsecutiveSameTool)
    }

    @Test
    fun `BudgetBuilder exposes maxConsecutiveSameTool via DSL`() {
        val b = BudgetBuilder()
        b.maxConsecutiveSameTool = 5
        assertEquals(5, b.build().maxConsecutiveSameTool)
    }

    @Test
    fun `throws when same tool invoked more than the cap in a row`() {
        // Cap = 2. The LLM emits the same tool name 3 times in successive turns.
        // The third call must trip the cap.
        val responses = ArrayDeque<LlmResponse>()
        repeat(3) { responses.add(LlmResponse.ToolCalls(listOf(ToolCall("noop", emptyMap())))) }
        responses.add(LlmResponse.Text("done"))
        val mock = ModelClient { _ -> responses.removeFirst() }

        val a = agent<String, String>("a") {
            model { ollama("llama3"); client = mock }
            budget { maxConsecutiveSameTool = 2; maxToolCalls = 100; maxTurns = 100 }
            tools { tool("noop", "") { _ -> "ok" } }
            skills { skill<String, String>("s", "s") { tools("noop") } }
        }

        val ex = assertThrows<BudgetExceededException> { a("input") }
        assertEquals(BudgetReason.CONSECUTIVE_TOOL, ex.reason)
        // Message must name the offending tool and the offending count.
        val msg = ex.message.orEmpty()
        assertTrue(msg.contains("noop"), "message should mention tool name: $msg")
        assertTrue(msg.contains("3"), "message should mention count: $msg")
    }

    @Test
    fun `cap of 1 trips on the second consecutive call to the same tool`() {
        // Edge case: cap = 1 means at most one consecutive call.
        val responses = ArrayDeque<LlmResponse>()
        repeat(2) { responses.add(LlmResponse.ToolCalls(listOf(ToolCall("noop", emptyMap())))) }
        responses.add(LlmResponse.Text("done"))
        val mock = ModelClient { _ -> responses.removeFirst() }

        val a = agent<String, String>("a") {
            model { ollama("llama3"); client = mock }
            budget { maxConsecutiveSameTool = 1; maxToolCalls = 100; maxTurns = 100 }
            tools { tool("noop", "") { _ -> "ok" } }
            skills { skill<String, String>("s", "s") { tools("noop") } }
        }

        val ex = assertThrows<BudgetExceededException> { a("input") }
        assertEquals(BudgetReason.CONSECUTIVE_TOOL, ex.reason)
    }

    @Test
    fun `different tool resets the counter`() {
        // Cap = 2. Sequence: noop, noop, OTHER, noop, noop, done.
        // After OTHER, the noop counter resets to 1, so the second pair of
        // noop calls reaches 2 (== cap, not over) and then completes.
        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.ToolCalls(listOf(ToolCall("noop", emptyMap()))))
        responses.add(LlmResponse.ToolCalls(listOf(ToolCall("noop", emptyMap()))))
        responses.add(LlmResponse.ToolCalls(listOf(ToolCall("other", emptyMap()))))
        responses.add(LlmResponse.ToolCalls(listOf(ToolCall("noop", emptyMap()))))
        responses.add(LlmResponse.ToolCalls(listOf(ToolCall("noop", emptyMap()))))
        responses.add(LlmResponse.Text("done"))
        val mock = ModelClient { _ -> responses.removeFirst() }

        val a = agent<String, String>("a") {
            model { ollama("llama3"); client = mock }
            budget { maxConsecutiveSameTool = 2; maxToolCalls = 100; maxTurns = 100 }
            tools {
                tool("noop", "") { _ -> "ok" }
                tool("other", "") { _ -> "ok" }
            }
            skills { skill<String, String>("s", "s") { tools("noop", "other") } }
        }

        // Should NOT throw: the counter resets across the OTHER call.
        assertEquals("done", a("input"))
    }

    @Test
    fun `null cap means no enforcement`() {
        // Default null — the LLM can spam the same tool indefinitely (within
        // maxToolCalls). Verify by emitting many identical calls under the cap.
        val responses = ArrayDeque<LlmResponse>()
        repeat(10) { responses.add(LlmResponse.ToolCalls(listOf(ToolCall("noop", emptyMap())))) }
        responses.add(LlmResponse.Text("done"))
        val mock = ModelClient { _ -> responses.removeFirst() }

        val a = agent<String, String>("a") {
            model { ollama("llama3"); client = mock }
            budget { maxToolCalls = 100; maxTurns = 100 }
            // No maxConsecutiveSameTool set — uncapped.
            tools { tool("noop", "") { _ -> "ok" } }
            skills { skill<String, String>("s", "s") { tools("noop") } }
        }

        assertEquals("done", a("input"))
    }

    @Test
    fun `multiple same-tool calls within ONE turn count toward the cap`() {
        // The LLM emits THREE tool calls (all noop) inside a single ToolCalls
        // response. With cap=2, the third one inside that batch trips.
        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.ToolCalls(listOf(
            ToolCall("noop", emptyMap()),
            ToolCall("noop", emptyMap()),
            ToolCall("noop", emptyMap()),
        )))
        responses.add(LlmResponse.Text("done"))
        val mock = ModelClient { _ -> responses.removeFirst() }

        val a = agent<String, String>("a") {
            model { ollama("llama3"); client = mock }
            budget { maxConsecutiveSameTool = 2; maxToolCalls = 100; maxTurns = 100 }
            tools { tool("noop", "") { _ -> "ok" } }
            skills { skill<String, String>("s", "s") { tools("noop") } }
        }

        val ex = assertThrows<BudgetExceededException> { a("input") }
        assertEquals(BudgetReason.CONSECUTIVE_TOOL, ex.reason)
    }
}
