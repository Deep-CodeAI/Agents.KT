package agents_engine.model

import agents_engine.core.agent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.fail

/**
 * #2888 (epic #2882, Pillar 3 v1) — `maxToolArgsBytes` hard-caps a single tool
 * call's argument size, denied **before** the executor runs. Resource-exhaustion
 * guard (attack A5): the model (often via injected input) emits a tool call with
 * enormous arguments. Unconditional hard cap like `PER_TOOL_TIMEOUT` — not
 * extendable via `onBudgetExceeded`.
 */
class MaxToolArgsBytesTest {

    private fun agentEmitting(call: ToolCall, cap: Long?, onRun: () -> Unit) =
        agent<String, String>("argscap") {
            lateinit var echo: Tool<Map<String, Any?>, Any?>
            val responses = ArrayDeque(
                listOf<LlmResponse>(
                    LlmResponse.ToolCalls(listOf(call)),
                    LlmResponse.Text("done"),
                ),
            )
            model { ollama("llama3"); client = ModelClient { _ -> responses.removeFirst() } }
            tools { echo = tool("echo", "") { _ -> onRun(); "ok" } }
            skills { skill<String, String>("s", "stub") { tools(echo) } }
            budget { maxToolArgsBytes = cap }
        }

    @Test fun `BudgetConfig maxToolArgsBytes default is null (no cap)`() {
        assertNull(BudgetConfig().maxToolArgsBytes)
    }

    @Test fun `BudgetBuilder exposes maxToolArgsBytes via DSL`() {
        val b = BudgetBuilder()
        b.maxToolArgsBytes = 4096
        assertEquals(4096L, b.build().maxToolArgsBytes)
    }

    @Test fun `BudgetReason has TOOL_ARGS_SIZE`() {
        assertEquals("TOOL_ARGS_SIZE", BudgetReason.TOOL_ARGS_SIZE.name)
    }

    @Test fun `an oversized tool call is denied before the executor runs`() {
        var executions = 0
        val big = mapOf("data" to "x".repeat(5_000))
        val a = agentEmitting(ToolCall(name = "echo", arguments = big), cap = 1_024) { executions++ }
        try {
            a("input")
            fail("expected BudgetExceededException(TOOL_ARGS_SIZE)")
        } catch (e: BudgetExceededException) {
            assertEquals(BudgetReason.TOOL_ARGS_SIZE, e.reason)
            assertEquals(0, executions, "executor must not run when args exceed the cap")
        }
    }

    @Test fun `the raw wire-arguments size is used when present`() {
        var executions = 0
        // Small parsed map but a huge raw wire form — the wire bytes are what count.
        val raw = "{\"a\":\"${"x".repeat(5_000)}\"}"
        val call = ToolCall(name = "echo", arguments = mapOf("a" to 1), rawArguments = raw)
        val a = agentEmitting(call, cap = 1_024) { executions++ }
        try {
            a("input")
            fail("expected BudgetExceededException(TOOL_ARGS_SIZE)")
        } catch (e: BudgetExceededException) {
            assertEquals(BudgetReason.TOOL_ARGS_SIZE, e.reason)
            assertEquals(0, executions)
        }
    }

    @Test fun `an under-cap tool call runs normally`() {
        var executions = 0
        val call = ToolCall(name = "echo", arguments = mapOf("data" to "small"))
        val a = agentEmitting(call, cap = 1_024) { executions++ }
        a("input")
        assertEquals(1, executions, "a within-cap call must execute")
    }

    @Test fun `a null cap leaves tool args unbounded (back-compat)`() {
        var executions = 0
        val big = mapOf("data" to "x".repeat(50_000))
        val a = agentEmitting(ToolCall(name = "echo", arguments = big), cap = null) { executions++ }
        a("input")
        assertEquals(1, executions, "null cap must not gate")
    }
}
