package agents_engine.model

import agents_engine.core.Agent
import agents_engine.core.AgentRuntimeContext
import agents_engine.core.SessionSnapshot
import agents_engine.core.agent
import org.junit.jupiter.api.assertThrows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * #2791 — the budget state lifted out of [executeAgentic] into [BudgetTracker] is now testable in
 * isolation (the point of the extraction). These pin the counter, threshold, cap-dispatch, and
 * snapshot contracts directly, without driving a full agentic loop.
 */
class BudgetTrackerTest {

    private fun stubAgent(build: (Agent<String, String>.() -> Unit) = {}): Agent<String, String> =
        agent("budget-test") {
            model { ollama("test"); client = ModelClient { _ -> LlmResponse.Text("x") } }
            skills { skill<String, String>("s", "s") { } }
            build()
        }

    private fun tracker(
        a: Agent<String, String>,
        onTurnCheckpoint: ((SessionSnapshot) -> Unit)? = null,
        messages: List<LlmMessage> = emptyList(),
        cumulative: TokenUsage? = null,
    ) = BudgetTracker(
        a, a.budgetConfig, null, AgentRuntimeContext.currentOrNew(),
        onTurnCheckpoint, { messages }, { cumulative },
    )

    @Test fun `recordConsecutiveTool counts a run and resets when a different tool intervenes`() {
        val t = tracker(stubAgent())
        t.recordConsecutiveTool("a")
        assertEquals(1, t.consecutiveSameTool)
        t.recordConsecutiveTool("a")
        assertEquals(2, t.consecutiveSameTool)
        t.recordConsecutiveTool("b")
        assertEquals(1, t.consecutiveSameTool, "a different tool resets the run")
        assertEquals("b", t.lastToolName)
    }

    @Test fun `maybeFireThreshold fires once per reason and only above the configured fraction`() {
        val fired = mutableListOf<Pair<BudgetReason, Double>>()
        val a = stubAgent { onBudgetThreshold(threshold = 0.5) { reason, pct -> fired += reason to pct } }
        val t = tracker(a)
        t.maybeFireThreshold(BudgetReason.TURNS, 0.4) // below threshold — no fire
        t.maybeFireThreshold(BudgetReason.TURNS, 0.6) // crosses — fire
        t.maybeFireThreshold(BudgetReason.TURNS, 0.9) // already fired — no second fire
        t.maybeFireThreshold(BudgetReason.TOKENS, 0.7) // different reason — fires independently
        assertEquals(listOf(BudgetReason.TURNS to 0.6, BudgetReason.TOKENS to 0.7), fired)
    }

    @Test fun `resolveCapDecision with no handler throws BudgetExceeded`() {
        val t = tracker(stubAgent())
        val ex = assertThrows<BudgetExceededException> {
            t.resolveCapDecision(BudgetReason.TURNS, 5, "over") { error("must not apply") }
        }
        assertEquals(BudgetReason.TURNS, ex.reason)
    }

    @Test fun `resolveCapDecision Extend above the current limit applies the new ceiling and does not throw`() {
        val a = stubAgent { onBudgetExceeded { _, current -> BudgetDecision.Extend(current + 5) } }
        val t = tracker(a)
        var applied = -1
        t.resolveCapDecision(BudgetReason.TOOL_CALLS, 2, "over") { applied = it }
        assertEquals(7, applied)
    }

    @Test fun `resolveCapDecision Extend not above the current limit still throws`() {
        val a = stubAgent { onBudgetExceeded { _, _ -> BudgetDecision.Extend(2) } }
        val t = tracker(a)
        assertThrows<BudgetExceededException> {
            t.resolveCapDecision(BudgetReason.TOOL_CALLS, 5, "over") { error("must not apply") }
        }
    }

    @Test fun `resolveCapDecision Checkpoint delivers a snapshot and throws BudgetCheckpoint`() {
        val a = stubAgent { onBudgetExceeded { _, _ -> BudgetDecision.Checkpoint } }
        var delivered: SessionSnapshot? = null
        val t = tracker(a, onTurnCheckpoint = { delivered = it })
        t.turns = 4
        val ex = assertThrows<BudgetCheckpointException> {
            t.resolveCapDecision(BudgetReason.DURATION, 1000, "over") { error("must not apply") }
        }
        assertEquals(BudgetReason.DURATION, ex.reason)
        assertSame(ex.snapshot, delivered, "same snapshot delivered to onTurnCheckpoint and carried on the exception")
        assertEquals(4, ex.snapshot.turns)
    }

    @Test fun `Checkpoint with no onTurnCheckpoint falls back to BudgetExceeded (Stop semantics)`() {
        val a = stubAgent { onBudgetExceeded { _, _ -> BudgetDecision.Checkpoint } }
        val t = tracker(a, onTurnCheckpoint = null)
        assertThrows<BudgetExceededException> {
            t.resolveCapDecision(BudgetReason.TURNS, 3, "over") { error("must not apply") }
        }
    }

    @Test fun `snapshot reflects the live counters and the pending interrupt id`() {
        val t = tracker(stubAgent())
        t.turns = 3
        t.toolCalls = 2
        t.toolCallLimit = 9
        val snap = t.snapshot("call-7")
        assertEquals(3, snap.turns)
        assertEquals(2, snap.toolCalls)
        assertEquals(9, snap.toolCallLimit)
        assertEquals("call-7", snap.pendingInterruptCallId)
    }

    @Test fun `accumulateUsage seeds from the first usage then sums totals`() {
        val u1 = TokenUsage(promptTokens = 10, completionTokens = 5)
        assertSame(u1, accumulateUsage(null, u1), "first non-null usage seeds the accumulator")
        val merged = accumulateUsage(u1, TokenUsage(promptTokens = 3, completionTokens = 2))
        assertEquals(20, merged.total)
    }
}
