package agents_engine.model

import agents_engine.core.agent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * #3377 — a self-re-entering agent (a tool that re-invokes its own agent) must be bounded by
 * `maxAgentDepth`, failing fast with `BudgetReason.AGENT_DEPTH` instead of recursing one full
 * agentic loop per level until the JVM stack overflows. Budgets bound a single loop; this pins the
 * cross-loop nesting bound that was missing.
 */
class AgentRecursionDepthTest {

    @Test
    fun `self-re-entering agent is bounded by maxAgentDepth and fails fast`() {
        lateinit var self: agents_engine.core.Agent<String, String>
        // A model that always asks to call the re-invoke tool — drives unbounded recursion absent a cap.
        val alwaysRecurse = ModelClient { _ -> LlmResponse.ToolCalls(listOf(ToolCall("recurse"))) }

        self = agent<String, String>("recursor") {
            model { ollama("stub"); client = alwaysRecurse }
            budget { maxAgentDepth = 3 }
            lateinit var recurse: Tool<Map<String, Any?>, Any?>
            tools { recurse = tool("recurse", "re-invoke self") { _ -> self("go") } }
            skills { skill<String, String>("s", "stub") { tools(recurse) } }
        }

        val ex = assertFailsWith<BudgetExceededException> { self("go") }
        assertEquals(BudgetReason.AGENT_DEPTH, ex.reason, "must fail for recursion depth, not another cap")
        assertTrue("maxAgentDepth" in (ex.message ?: ""), "message names the cap: ${ex.message}")
    }
}
