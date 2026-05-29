package agents_engine.model

import agents_engine.core.agent
import kotlin.time.Duration.Companion.milliseconds
import org.junit.jupiter.api.assertThrows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #2750 — `onBudgetExceeded` was wired only for TOOL_CALLS in #2412. This
 * suite pins the broadened coverage to TURNS / DURATION / TOKENS /
 * CONSECUTIVE_TOOL. PER_TOOL_TIMEOUT stays unconditionally throwing
 * (extending mid-tool needs interrupt semantics — separate ticket).
 *
 * Units when the handler returns `BudgetDecision.Extend(newLimit)`:
 * - TOOL_CALLS / TURNS / TOKENS / CONSECUTIVE_TOOL → integer count
 * - DURATION → milliseconds
 */
class AgentBudgetExtensionAllReasonsTest {

    // -------- TURNS --------

    @Test
    fun `TURNS Extend raises the turn cap and the loop continues`() {
        val responses = ArrayDeque<LlmResponse>()
        // Each turn produces a tool call — keeps the loop iterating.
        repeat(6) { responses.add(LlmResponse.ToolCalls(listOf(ToolCall("step", emptyMap())))) }
        responses.add(LlmResponse.Text("done"))
        val mock = ModelClient { _ -> responses.removeFirst() }

        val extensions = mutableListOf<Pair<BudgetReason, Int>>()
        val a = agent<String, String>("a") {
            lateinit var step: Tool<Map<String, Any?>, Any?>
            model { ollama("t"); client = mock }
            budget { maxTurns = 3; maxToolCalls = 100; maxConsecutiveSameTool = 100 }
            tools { step = tool("step", "") { _ -> "ok" } }
            skills { skill<String, String>("s", "") { tools(step) } }
            onBudgetExceeded { reason, current ->
                extensions += reason to current
                if (reason == BudgetReason.TURNS) BudgetDecision.Extend(current + 4)
                else BudgetDecision.Stop
            }
        }

        assertEquals("done", a("go"))
        assertEquals(listOf(BudgetReason.TURNS to 3), extensions)
    }

    @Test
    fun `TURNS without a handler still throws (baseline regression)`() {
        val responses = ArrayDeque<LlmResponse>()
        repeat(6) { responses.add(LlmResponse.ToolCalls(listOf(ToolCall("step", emptyMap())))) }
        val mock = ModelClient { _ -> responses.removeFirst() }
        val a = agent<String, String>("a") {
            lateinit var step: Tool<Map<String, Any?>, Any?>
            model { ollama("t"); client = mock }
            budget { maxTurns = 3; maxToolCalls = 100; maxConsecutiveSameTool = 100 }
            tools { step = tool("step", "") { _ -> "ok" } }
            skills { skill<String, String>("s", "") { tools(step) } }
        }
        val ex = assertThrows<BudgetExceededException> { a("go") }
        assertEquals(BudgetReason.TURNS, ex.reason)
    }

    @Test
    fun `TURNS Stop throws like the default`() {
        val responses = ArrayDeque<LlmResponse>()
        repeat(6) { responses.add(LlmResponse.ToolCalls(listOf(ToolCall("step", emptyMap())))) }
        val mock = ModelClient { _ -> responses.removeFirst() }
        val a = agent<String, String>("a") {
            lateinit var step: Tool<Map<String, Any?>, Any?>
            model { ollama("t"); client = mock }
            budget { maxTurns = 3; maxToolCalls = 100; maxConsecutiveSameTool = 100 }
            tools { step = tool("step", "") { _ -> "ok" } }
            skills { skill<String, String>("s", "") { tools(step) } }
            onBudgetExceeded { _, _ -> BudgetDecision.Stop }
        }
        val ex = assertThrows<BudgetExceededException> { a("go") }
        assertEquals(BudgetReason.TURNS, ex.reason)
    }

    // -------- TOKENS --------

    @Test
    fun `TOKENS Extend raises the token cap and the loop continues`() {
        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.ToolCalls(
            listOf(ToolCall("step", emptyMap())),
            tokenUsage = TokenUsage(promptTokens = 100, completionTokens = 100, provider = "x", model = "y"),
        ))
        responses.add(LlmResponse.Text(
            "done",
            tokenUsage = TokenUsage(promptTokens = 50, completionTokens = 50, provider = "x", model = "y"),
        ))
        val mock = ModelClient { _ -> responses.removeFirst() }

        val extensions = mutableListOf<Pair<BudgetReason, Int>>()
        val a = agent<String, String>("a") {
            lateinit var step: Tool<Map<String, Any?>, Any?>
            model { ollama("t"); client = mock }
            // Cap of 100 — first turn alone reports 200, exceeds.
            budget { maxTokens = 100; maxTurns = 50; maxToolCalls = 100; maxConsecutiveSameTool = 100 }
            tools { step = tool("step", "") { _ -> "ok" } }
            skills { skill<String, String>("s", "") { tools(step) } }
            onBudgetExceeded { reason, current ->
                extensions += reason to current
                if (reason == BudgetReason.TOKENS) BudgetDecision.Extend(current * 10)
                else BudgetDecision.Stop
            }
        }

        assertEquals("done", a("go"))
        assertEquals(listOf(BudgetReason.TOKENS to 100), extensions)
    }

    @Test
    fun `TOKENS without a handler throws (regression)`() {
        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.ToolCalls(
            listOf(ToolCall("step", emptyMap())),
            tokenUsage = TokenUsage(promptTokens = 100, completionTokens = 100, provider = "x", model = "y"),
        ))
        val mock = ModelClient { _ -> responses.removeFirst() }
        val a = agent<String, String>("a") {
            lateinit var step: Tool<Map<String, Any?>, Any?>
            model { ollama("t"); client = mock }
            budget { maxTokens = 50; maxTurns = 50; maxToolCalls = 100; maxConsecutiveSameTool = 100 }
            tools { step = tool("step", "") { _ -> "ok" } }
            skills { skill<String, String>("s", "") { tools(step) } }
        }
        val ex = assertThrows<BudgetExceededException> { a("go") }
        assertEquals(BudgetReason.TOKENS, ex.reason)
    }

    // -------- CONSECUTIVE_TOOL --------

    @Test
    fun `CONSECUTIVE_TOOL Extend raises the cap and the loop continues`() {
        val responses = ArrayDeque<LlmResponse>()
        repeat(5) { responses.add(LlmResponse.ToolCalls(listOf(ToolCall("step", emptyMap())))) }
        responses.add(LlmResponse.Text("done"))
        val mock = ModelClient { _ -> responses.removeFirst() }

        val extensions = mutableListOf<Pair<BudgetReason, Int>>()
        val a = agent<String, String>("a") {
            lateinit var step: Tool<Map<String, Any?>, Any?>
            model { ollama("t"); client = mock }
            // Cap of 2 means 3rd consecutive same-tool call trips
            budget { maxConsecutiveSameTool = 2; maxToolCalls = 100; maxTurns = 50 }
            tools { step = tool("step", "") { _ -> "ok" } }
            skills { skill<String, String>("s", "") { tools(step) } }
            onBudgetExceeded { reason, current ->
                extensions += reason to current
                if (reason == BudgetReason.CONSECUTIVE_TOOL) BudgetDecision.Extend(current + 10)
                else BudgetDecision.Stop
            }
        }

        assertEquals("done", a("go"))
        assertEquals(listOf(BudgetReason.CONSECUTIVE_TOOL to 2), extensions)
    }

    @Test
    fun `CONSECUTIVE_TOOL without a handler throws (regression)`() {
        val responses = ArrayDeque<LlmResponse>()
        repeat(5) { responses.add(LlmResponse.ToolCalls(listOf(ToolCall("step", emptyMap())))) }
        val mock = ModelClient { _ -> responses.removeFirst() }
        val a = agent<String, String>("a") {
            lateinit var step: Tool<Map<String, Any?>, Any?>
            model { ollama("t"); client = mock }
            budget { maxConsecutiveSameTool = 2; maxToolCalls = 100; maxTurns = 50 }
            tools { step = tool("step", "") { _ -> "ok" } }
            skills { skill<String, String>("s", "") { tools(step) } }
        }
        val ex = assertThrows<BudgetExceededException> { a("go") }
        assertEquals(BudgetReason.CONSECUTIVE_TOOL, ex.reason)
    }

    // -------- DURATION --------

    @Test
    fun `DURATION Extend raises the cap in millis and continues — handler path`() {
        // Tight wall-clock: 1ms cap, a tool that sleeps 50ms — the first
        // post-tool turn-top check will already be over budget.
        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.ToolCalls(listOf(ToolCall("step", emptyMap()))))
        responses.add(LlmResponse.Text("done"))
        val mock = ModelClient { _ -> responses.removeFirst() }

        val extensions = mutableListOf<Pair<BudgetReason, Int>>()
        val a = agent<String, String>("a") {
            lateinit var step: Tool<Map<String, Any?>, Any?>
            model { ollama("t"); client = mock }
            budget { maxDuration = 1.milliseconds; maxToolCalls = 100; maxTurns = 50; maxConsecutiveSameTool = 100 }
            tools { step = tool("step", "") { _ -> Thread.sleep(50); "ok" } }
            skills { skill<String, String>("s", "") { tools(step) } }
            onBudgetExceeded { reason, current ->
                extensions += reason to current
                if (reason == BudgetReason.DURATION) BudgetDecision.Extend(10_000) // 10s — generous
                else BudgetDecision.Stop
            }
        }

        assertEquals("done", a("go"))
        assertTrue(
            extensions.isNotEmpty() && extensions.all { it.first == BudgetReason.DURATION },
            "should have at least one DURATION extension, got: $extensions",
        )
    }

    @Test
    fun `DURATION without a handler throws (regression)`() {
        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.ToolCalls(listOf(ToolCall("step", emptyMap()))))
        responses.add(LlmResponse.Text("done"))
        val mock = ModelClient { _ -> responses.removeFirst() }
        val a = agent<String, String>("a") {
            lateinit var step: Tool<Map<String, Any?>, Any?>
            model { ollama("t"); client = mock }
            budget { maxDuration = 1.milliseconds; maxToolCalls = 100; maxTurns = 50; maxConsecutiveSameTool = 100 }
            tools { step = tool("step", "") { _ -> Thread.sleep(50); "ok" } }
            skills { skill<String, String>("s", "") { tools(step) } }
        }
        val ex = assertThrows<BudgetExceededException> { a("go") }
        assertEquals(BudgetReason.DURATION, ex.reason)
    }
}
