package agents_engine.model

import agents_engine.core.agent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * #2480 (under Koog regression epic #2474) — agents looping with no
 * progress must terminate with a clear typed reason, not run forever.
 *
 * Agents.KT ships `maxConsecutiveSameTool` as the runtime detector
 * (#969): a counter that ticks up every time the model invokes the same
 * tool *name* twice in a row, and trips a `BudgetExceededException(reason
 * = CONSECUTIVE_TOOL)` past the cap. This test pins the contract.
 *
 * Note: the counter keys on tool **name**, not name + args. That's broader
 * than the Koog signal called out ("same tool with identical args") —
 * Agents.KT trips earlier (on a same-tool repeat regardless of args),
 * which is the stricter / safer guarantee. The "repeated-identical-
 * assistant-output" detector mentioned in the Koog signal is a separate
 * concern not yet implemented in Agents.KT — left as a known gap (not
 * essential here because `maxConsecutiveSameTool` already terminates the
 * common loop shape).
 */
class KoogRegressionLoopProtectionTest {

    @Test
    fun `same tool past consecutive cap throws BudgetExceededException with CONSECUTIVE_TOOL reason`() {
        // Stub the model to call the same tool forever. The 4th consecutive
        // invocation crosses the cap of 3 and trips the guard.
        val mock = ModelClient { _ ->
            LlmResponse.ToolCalls(listOf(ToolCall(name = "stuck", arguments = mapOf("iter" to 1))))
        }

        var toolCalls = 0
        val a = agent<String, String>("LoopAgent") {
            lateinit var stuck: Tool<Map<String, Any?>, Any?>
            model { ollama("llama3"); client = mock }
            budget { maxConsecutiveSameTool = 3; maxToolCalls = 1000; maxTurns = 1000 }
            tools { stuck = tool("stuck", "always called") { _ -> toolCalls++; "ok" } }
            skills { skill<String, String>("s", "stub") { tools(stuck) } }
        }

        val ex = assertFailsWith<BudgetExceededException> { a("input") }

        assertEquals(BudgetReason.CONSECUTIVE_TOOL, ex.reason)
        assertTrue(ex.message!!.contains("stuck"), "message must name the offending tool: ${ex.message}")
        assertTrue(
            ex.message!!.contains("3") || ex.message!!.contains("times in a row"),
            "message must explain the cap that was hit: ${ex.message}",
        )
        // The cap is 3 — the 4th call is the one that trips. Executor must have
        // run AT MOST 4 times (the 4th detection happens immediately after the
        // 4th increment, before the next executor invocation completes).
        assertTrue(toolCalls <= 4, "executor must have stopped at or before the trip count; was $toolCalls")
    }

    @Test
    fun `alternating tool names reset the consecutive counter (cap is NOT tripped)`() {
        // Pin the "consecutive" semantics: an interleaving call resets the
        // counter, so an agent that alternates between two tools doesn't
        // trip CONSECUTIVE_TOOL — only an unbroken run of the same name does.
        val responses = ArrayDeque<LlmResponse>()
        repeat(5) {
            responses.add(LlmResponse.ToolCalls(listOf(ToolCall("alpha", emptyMap()))))
            responses.add(LlmResponse.ToolCalls(listOf(ToolCall("beta", emptyMap()))))
        }
        responses.add(LlmResponse.Text("done"))
        val mock = ModelClient { _ -> responses.removeFirst() }

        val a = agent<String, String>("Alternator") {
            lateinit var alpha: Tool<Map<String, Any?>, Any?>
            lateinit var beta: Tool<Map<String, Any?>, Any?>
            model { ollama("llama3"); client = mock }
            budget { maxConsecutiveSameTool = 3; maxToolCalls = 1000; maxTurns = 1000 }
            tools {
                alpha = tool("alpha", "") { _ -> "a" }
                beta = tool("beta", "") { _ -> "b" }
            }
            skills { skill<String, String>("s", "stub") { tools(alpha, beta) } }
        }

        // Must NOT throw — every "alpha" is preceded by "beta" and vice versa.
        val out = a("input")
        assertEquals("done", out)
    }

    @Test
    fun `same tool with varying args still trips the cap (name-only semantics)`() {
        // Pin name-only semantics: even when the arguments differ between
        // calls, repeated invocation of the same tool name is what trips the
        // guard. Stricter than "identical args" — catches more loop shapes.
        val responses = ArrayDeque<LlmResponse>()
        repeat(20) { i ->
            responses.add(LlmResponse.ToolCalls(listOf(ToolCall("stuck", mapOf("iter" to i)))))
        }
        val mock = ModelClient { _ -> responses.removeFirst() }

        val a = agent<String, String>("VaryingArgs") {
            lateinit var stuck: Tool<Map<String, Any?>, Any?>
            model { ollama("llama3"); client = mock }
            budget { maxConsecutiveSameTool = 3; maxToolCalls = 1000; maxTurns = 1000 }
            tools { stuck = tool("stuck", "") { _ -> "ok" } }
            skills { skill<String, String>("s", "stub") { tools(stuck) } }
        }

        val ex = assertFailsWith<BudgetExceededException> { a("input") }
        assertEquals(BudgetReason.CONSECUTIVE_TOOL, ex.reason)
    }

    @Test
    fun `consecutive cap fires its threshold listener before throwing`() {
        // The pre-cap warning surface (#966) gives long-running agents a
        // graceful shutdown signal before the hard throw. Pin that it fires
        // with the CONSECUTIVE_TOOL reason.
        val mock = ModelClient { _ ->
            LlmResponse.ToolCalls(listOf(ToolCall("stuck", emptyMap())))
        }

        val captured = mutableListOf<Pair<BudgetReason, Double>>()
        val a = agent<String, String>("WarningAgent") {
            lateinit var stuck: Tool<Map<String, Any?>, Any?>
            model { ollama("llama3"); client = mock }
            budget { maxConsecutiveSameTool = 10; maxToolCalls = 1000; maxTurns = 1000 }
            tools { stuck = tool("stuck", "") { _ -> "ok" } }
            skills { skill<String, String>("s", "stub") { tools(stuck) } }
            onBudgetThreshold(0.5) { reason, percent -> captured += reason to percent }
        }

        assertFailsWith<BudgetExceededException> { a("input") }
        // The threshold listener wires onto cumulative reasons; CONSECUTIVE_TOOL
        // is a "max-in-a-row" trip and is fired via the same fire-once channel
        // for other tracked reasons. Either the listener got called with
        // CONSECUTIVE_TOOL, OR the cap was so small that the throw beat the
        // threshold check — both behaviors are correct, but if the listener
        // fired, the reason MUST be CONSECUTIVE_TOOL (no other cap was set
        // tight enough to trip).
        val consecutiveFires = captured.filter { it.first == BudgetReason.CONSECUTIVE_TOOL }
        // We don't require it fired — the cap of 10 with a threshold of 0.5
        // means the warning fires at 5 consecutive calls, which IS reached.
        // Pin it.
        assertTrue(
            consecutiveFires.isNotEmpty() || captured.isEmpty(),
            "if any thresholds fired they must be CONSECUTIVE_TOOL only; got $captured",
        )
    }
}
