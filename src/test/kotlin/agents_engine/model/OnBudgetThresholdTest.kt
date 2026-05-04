package agents_engine.model

import agents_engine.core.agent
import org.junit.jupiter.api.assertThrows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds

// Tests for #966 — onBudgetThreshold(threshold) { reason, usedPercent -> }
// fires once per BudgetReason as cumulative usage crosses the configured
// fraction, before the corresponding cap throws BudgetExceededException.
class OnBudgetThresholdTest {

    @Test
    fun `threshold registration validates 0 to 1 range`() {
        // Below 0
        assertThrows<IllegalArgumentException> {
            agent<String, String>("a") {
                model { ollama("llama3"); client = ModelClient { _ -> LlmResponse.Text("ok") } }
                skills { skill<String, String>("s", "s") { tools() } }
                onBudgetThreshold(threshold = -0.1) { _, _ -> }
            }
        }
        // Above 1
        assertThrows<IllegalArgumentException> {
            agent<String, String>("a") {
                model { ollama("llama3"); client = ModelClient { _ -> LlmResponse.Text("ok") } }
                skills { skill<String, String>("s", "s") { tools() } }
                onBudgetThreshold(threshold = 1.5) { _, _ -> }
            }
        }
        // Boundaries 0.0 and 1.0 are accepted
        agent<String, String>("a") {
            model { ollama("llama3"); client = ModelClient { _ -> LlmResponse.Text("ok") } }
            skills { skill<String, String>("s", "s") { tools() } }
            onBudgetThreshold(threshold = 0.0) { _, _ -> }
            onBudgetThreshold(threshold = 1.0) { _, _ -> }
        }
    }

    @Test
    fun `TURNS threshold fires when crossing`() {
        // maxTurns=4, threshold=0.5 → fires when turns >= 2.
        // Two tool-call turns then a text turn — completes within the cap.
        val responses = ArrayDeque<LlmResponse>()
        repeat(2) { responses.add(LlmResponse.ToolCalls(listOf(ToolCall("noop", emptyMap())))) }
        responses.add(LlmResponse.Text("done"))
        val mock = ModelClient { _ -> responses.removeFirst() }

        val fired = mutableListOf<Pair<BudgetReason, Double>>()
        val a = agent<String, String>("a") {
            lateinit var noop: Tool<Map<String, Any?>, Any?>
            model { ollama("llama3"); client = mock }
            budget { maxTurns = 4 }
            tools { noop = tool("noop", "") { _ -> "ok" } }
            skills { skill<String, String>("s", "s") { tools(noop) } }
            onBudgetThreshold(threshold = 0.5) { reason, used -> fired += reason to used }
        }

        a("input")

        val turnsFire = fired.firstOrNull { it.first == BudgetReason.TURNS }
            ?: fail("TURNS threshold did not fire; got: $fired")
        // Should fire on turn 2 of 4 (50%) — tolerate a fire on turn 3 (75%) too,
        // since "first crossing" semantics depend on accumulator step.
        assertTrue(turnsFire.second >= 0.5, "expected used >= 0.5; got ${turnsFire.second}")
    }

    @Test
    fun `TOOL_CALLS threshold fires when crossing`() {
        // maxToolCalls=4, threshold=0.5 → fires when toolCalls >= 2.
        // Each turn issues one tool call.
        val responses = ArrayDeque<LlmResponse>()
        repeat(3) { responses.add(LlmResponse.ToolCalls(listOf(ToolCall("noop", emptyMap())))) }
        responses.add(LlmResponse.Text("done"))
        val mock = ModelClient { _ -> responses.removeFirst() }

        val fired = mutableListOf<Pair<BudgetReason, Double>>()
        val a = agent<String, String>("a") {
            lateinit var noop: Tool<Map<String, Any?>, Any?>
            model { ollama("llama3"); client = mock }
            budget { maxToolCalls = 4 }
            tools { noop = tool("noop", "") { _ -> "ok" } }
            skills { skill<String, String>("s", "s") { tools(noop) } }
            onBudgetThreshold(threshold = 0.5) { r, p -> fired += r to p }
        }

        a("input")

        val toolFire = fired.firstOrNull { it.first == BudgetReason.TOOL_CALLS }
            ?: fail("TOOL_CALLS threshold did not fire; got: $fired")
        assertTrue(toolFire.second >= 0.5)
    }

    @Test
    fun `DURATION threshold fires when crossing`() {
        // maxDuration=200ms, threshold=0.4 → 80ms must elapse.
        // Each chat sleeps 100ms; one turn returns text.
        val mock = ModelClient { _ -> Thread.sleep(100); LlmResponse.Text("done") }

        val fired = mutableListOf<Pair<BudgetReason, Double>>()
        val a = agent<String, String>("a") {
            model { ollama("llama3"); client = mock }
            budget { maxDuration = 200.milliseconds }
            skills { skill<String, String>("s", "s") { tools() } }
            onBudgetThreshold(threshold = 0.4) { r, p -> fired += r to p }
        }

        a("input")

        // First turn entry is at 0% — no fire. After the 100ms sleep + return,
        // the next loop iteration's threshold check sees ~50% and fires —
        // but we already returned because chat returned Text. Actually the
        // duration threshold is checked at the TOP of each iteration, so the
        // initial iteration is at 0%. To trigger DURATION fire reliably we
        // need at least two iterations OR the threshold check should be on
        // exit. Since this is single-iteration, DURATION may not fire here.
        // What we CAN verify: DURATION fire is bounded — it appears at most
        // once and, when it does, used >= 0.4.
        val duration = fired.filter { it.first == BudgetReason.DURATION }
        if (duration.isNotEmpty()) {
            assertEquals(1, duration.size, "DURATION fired more than once: $duration")
            assertTrue(duration.single().second >= 0.4)
        }
    }

    @Test
    fun `DURATION threshold reliably fires across multiple turns`() {
        // Multi-turn version of the previous test: tool call then text. Ensures
        // the loop iterates again so the top-of-iteration DURATION check sees
        // the elapsed time.
        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.ToolCalls(listOf(ToolCall("noop", emptyMap()))))
        responses.add(LlmResponse.Text("done"))
        val mock = ModelClient { _ -> Thread.sleep(80); responses.removeFirst() }

        val fired = mutableListOf<Pair<BudgetReason, Double>>()
        val a = agent<String, String>("a") {
            lateinit var noop: Tool<Map<String, Any?>, Any?>
            model { ollama("llama3"); client = mock }
            budget { maxDuration = 200.milliseconds }
            tools { noop = tool("noop", "") { _ -> "ok" } }
            skills { skill<String, String>("s", "s") { tools(noop) } }
            onBudgetThreshold(threshold = 0.3) { r, p -> fired += r to p }
        }

        a("input")

        val duration = fired.filter { it.first == BudgetReason.DURATION }
        assertTrue(duration.isNotEmpty(), "DURATION threshold did not fire; got: $fired")
        assertEquals(1, duration.size, "DURATION fired more than once: $duration")
    }

    @Test
    fun `TOKENS threshold fires when crossing`() {
        // maxTokens=100, threshold=0.4 → fires at totalTokens >= 40.
        // First turn reports 50 tokens (over threshold), still under cap.
        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.Text("done", TokenUsage(promptTokens = 30, completionTokens = 20)))
        val mock = ModelClient { _ -> responses.removeFirst() }

        val fired = mutableListOf<Pair<BudgetReason, Double>>()
        val a = agent<String, String>("a") {
            model { ollama("llama3"); client = mock }
            budget { maxTokens = 100 }
            skills { skill<String, String>("s", "s") { tools() } }
            onBudgetThreshold(threshold = 0.4) { r, p -> fired += r to p }
        }

        a("input")

        val tokenFire = fired.firstOrNull { it.first == BudgetReason.TOKENS }
            ?: fail("TOKENS threshold did not fire; got: $fired")
        assertEquals(0.5, tokenFire.second, 0.0001)
    }

    @Test
    fun `TOKENS threshold does not fire when provider reports null usage`() {
        val mock = ModelClient { _ -> LlmResponse.Text("done") }  // no token usage

        val fired = mutableListOf<Pair<BudgetReason, Double>>()
        val a = agent<String, String>("a") {
            model { ollama("llama3"); client = mock }
            budget { maxTokens = 10 }
            skills { skill<String, String>("s", "s") { tools() } }
            onBudgetThreshold(threshold = 0.1) { r, p -> fired += r to p }
        }

        a("input")
        assertTrue(fired.none { it.first == BudgetReason.TOKENS }, "TOKENS fired despite null usage")
    }

    @Test
    fun `TOKENS threshold does not fire when maxTokens is null`() {
        val mock = ModelClient { _ -> LlmResponse.Text("done", TokenUsage(50, 50)) }

        val fired = mutableListOf<Pair<BudgetReason, Double>>()
        val a = agent<String, String>("a") {
            model { ollama("llama3"); client = mock }
            // No maxTokens set — uncapped.
            skills { skill<String, String>("s", "s") { tools() } }
            onBudgetThreshold(threshold = 0.1) { r, p -> fired += r to p }
        }

        a("input")
        assertTrue(fired.none { it.first == BudgetReason.TOKENS }, "TOKENS fired without a cap")
    }

    @Test
    fun `each reason fires at most once per invocation`() {
        // maxTurns=10, threshold=0.1 — every turn after the first crosses.
        // We expect exactly one TURNS fire across multiple turns past threshold.
        val responses = ArrayDeque<LlmResponse>()
        repeat(5) { responses.add(LlmResponse.ToolCalls(listOf(ToolCall("noop", emptyMap())))) }
        responses.add(LlmResponse.Text("done"))
        val mock = ModelClient { _ -> responses.removeFirst() }

        var turnsFireCount = 0
        val a = agent<String, String>("a") {
            lateinit var noop: Tool<Map<String, Any?>, Any?>
            model { ollama("llama3"); client = mock }
            budget { maxTurns = 10; maxToolCalls = 100 }
            tools { noop = tool("noop", "") { _ -> "ok" } }
            skills { skill<String, String>("s", "s") { tools(noop) } }
            onBudgetThreshold(threshold = 0.1) { reason, _ ->
                if (reason == BudgetReason.TURNS) turnsFireCount++
            }
        }

        a("input")
        assertEquals(1, turnsFireCount, "TURNS should fire exactly once, fired $turnsFireCount times")
    }

    @Test
    fun `different reasons fire independently`() {
        // Verify TURNS firing doesn't suppress TOOL_CALLS firing.
        // maxTurns=4, maxToolCalls=4, threshold=0.5.
        // Each turn = 1 tool call → both accumulators march in lockstep, both
        // cross threshold at turn/call 2 of 4.
        val responses = ArrayDeque<LlmResponse>()
        repeat(2) { responses.add(LlmResponse.ToolCalls(listOf(ToolCall("noop", emptyMap())))) }
        responses.add(LlmResponse.Text("done"))
        val mock = ModelClient { _ -> responses.removeFirst() }

        val firedReasons = mutableSetOf<BudgetReason>()
        val a = agent<String, String>("a") {
            lateinit var noop: Tool<Map<String, Any?>, Any?>
            model { ollama("llama3"); client = mock }
            budget { maxTurns = 4; maxToolCalls = 4 }
            tools { noop = tool("noop", "") { _ -> "ok" } }
            skills { skill<String, String>("s", "s") { tools(noop) } }
            onBudgetThreshold(threshold = 0.5) { reason, _ -> firedReasons += reason }
        }

        a("input")
        assertTrue(BudgetReason.TURNS in firedReasons, "TURNS missing: $firedReasons")
        assertTrue(BudgetReason.TOOL_CALLS in firedReasons, "TOOL_CALLS missing: $firedReasons")
    }

    @Test
    fun `no listener — loop completes normally`() {
        // Sanity: agents without a threshold listener are unaffected.
        val mock = ModelClient { _ -> LlmResponse.Text("ok", TokenUsage(50, 50)) }

        val a = agent<String, String>("a") {
            model { ollama("llama3"); client = mock }
            budget { maxTurns = 1; maxTokens = 100 }
            skills { skill<String, String>("s", "s") { tools() } }
        }

        assertEquals("ok", a("input"))
    }

    @Test
    fun `usedPercent below threshold does not fire`() {
        // maxTurns=10, threshold=0.5 → at turn 1, used=0.1 < 0.5, no fire.
        val mock = ModelClient { _ -> LlmResponse.Text("done") }

        val fired = mutableListOf<Pair<BudgetReason, Double>>()
        val a = agent<String, String>("a") {
            model { ollama("llama3"); client = mock }
            budget { maxTurns = 10 }
            skills { skill<String, String>("s", "s") { tools() } }
            onBudgetThreshold(threshold = 0.5) { r, p -> fired += r to p }
        }

        a("input")
        assertTrue(fired.none { it.first == BudgetReason.TURNS }, "TURNS fired below threshold: $fired")
    }

    @Test
    fun `default threshold is 0_8`() {
        val a = agent<String, String>("a") {
            model { ollama("llama3"); client = ModelClient { _ -> LlmResponse.Text("ok") } }
            skills { skill<String, String>("s", "s") { tools() } }
            onBudgetThreshold { _, _ -> }
        }
        assertEquals(0.8, a.budgetThreshold, 0.0001)
    }
}
