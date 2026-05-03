package agents_engine.model

import agents_engine.core.agent
import org.junit.jupiter.api.assertThrows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

// Tests for #963 — token-based budget control.
// Plumbing: Ollama reports prompt_eval_count + eval_count → ModelClient
// surfaces TokenUsage on LlmResponse → AgenticLoop accumulates → throws
// BudgetExceededException(TOKENS) when over cap.
class MaxTokensBudgetTest {

    @Test
    fun `TokenUsage total is the sum of prompt and completion`() {
        val u = TokenUsage(promptTokens = 30, completionTokens = 12)
        assertEquals(42, u.total)
    }

    @Test
    fun `LlmResponse Text exposes tokenUsage when constructed with one`() {
        val r = LlmResponse.Text("hello", TokenUsage(10, 5))
        val usage = r.tokenUsage
        assertNotNull(usage)
        assertEquals(15, usage.total)
    }

    @Test
    fun `LlmResponse ToolCalls exposes tokenUsage when constructed with one`() {
        val r = LlmResponse.ToolCalls(emptyList(), TokenUsage(20, 7))
        val usage = r.tokenUsage
        assertNotNull(usage)
        assertEquals(27, usage.total)
    }

    @Test
    fun `LlmResponse default tokenUsage is null (back-compat)`() {
        // Existing call sites (FakeModelClient { LlmResponse.Text("x") })
        // must continue to work without specifying token usage.
        assertNull(LlmResponse.Text("hi").tokenUsage)
        assertNull(LlmResponse.ToolCalls(emptyList()).tokenUsage)
    }

    @Test
    fun `BudgetConfig maxTokens default is null (no cap)`() {
        assertNull(BudgetConfig().maxTokens)
    }

    @Test
    fun `BudgetBuilder exposes maxTokens via DSL`() {
        val b = BudgetBuilder()
        b.maxTokens = 1000
        assertEquals(1000, b.build().maxTokens)
    }

    @Test
    fun `OllamaClient parseResponse extracts both prompt and completion counts`() {
        // Realistic Ollama response shape — token counts at the root, not on `message`.
        val body = """
            {
              "model": "llama3",
              "message": {"role": "assistant", "content": "hello"},
              "done": true,
              "prompt_eval_count": 25,
              "eval_count": 8
            }
        """.trimIndent()
        val client = OllamaClient(model = "llama3")
        val resp = client.parseResponse(body)
        val usage = resp.tokenUsage
        assertNotNull(usage)
        assertEquals(25, usage.promptTokens)
        assertEquals(8, usage.completionTokens)
        assertEquals(33, usage.total)
    }

    @Test
    fun `OllamaClient parseResponse drops partial token reports`() {
        // If only one of prompt_eval_count / eval_count is present, the count
        // is untrustworthy — surface it as null rather than half-attributing.
        val body = """
            {
              "model": "llama3",
              "message": {"role": "assistant", "content": "hi"},
              "done": true,
              "prompt_eval_count": 10
            }
        """.trimIndent()
        val resp = OllamaClient(model = "llama3").parseResponse(body)
        assertNull(resp.tokenUsage)
    }

    @Test
    fun `OllamaClient parseResponse handles missing token counts`() {
        // Provider didn't report anything — null, not zero.
        val body = """
            {
              "model": "llama3",
              "message": {"role": "assistant", "content": "hi"},
              "done": true
            }
        """.trimIndent()
        val resp = OllamaClient(model = "llama3").parseResponse(body)
        assertNull(resp.tokenUsage)
    }

    @Test
    fun `agentic loop accumulates tokens across turns`() {
        // Two turns: a tool call followed by a final text. Cap is generous
        // so the loop succeeds; we then verify the cumulative count by
        // observing that a tighter cap would have tripped (separate test).
        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.ToolCalls(
            listOf(ToolCall(name = "noop", arguments = emptyMap())),
            TokenUsage(promptTokens = 10, completionTokens = 5),
        ))
        responses.add(LlmResponse.Text(
            "done",
            TokenUsage(promptTokens = 15, completionTokens = 7),
        ))
        val mock = ModelClient { _ -> responses.removeFirst() }

        val a = agent<String, String>("a") {
            model { ollama("llama3"); client = mock }
            budget { maxTokens = 100 }
            tools { tool("noop", "") { _ -> "ok" } }
            skills { skill<String, String>("s", "s") { tools("noop") } }
        }

        val out = a("input")
        assertEquals("done", out)
    }

    @Test
    fun `agentic loop throws BudgetExceededException(TOKENS) when sum exceeds maxTokens`() {
        // First turn alone (10 + 5 = 15) is over the cap of 10.
        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.Text(
            "done",
            TokenUsage(promptTokens = 10, completionTokens = 5),
        ))
        val mock = ModelClient { _ -> responses.removeFirst() }

        val a = agent<String, String>("a") {
            model { ollama("llama3"); client = mock }
            budget { maxTokens = 10 }
            skills { skill<String, String>("s", "s") { tools() } }
        }

        val ex = assertThrows<BudgetExceededException> { a("input") }
        assertEquals(BudgetReason.TOKENS, ex.reason)
        // Message should mention both the cap and the actual usage so users
        // can see how badly they overshot.
        val msg = ex.message.orEmpty()
        assertEquals(true, msg.contains("10"), "message should mention cap: $msg")
        assertEquals(true, msg.contains("15"), "message should mention used: $msg")
    }

    @Test
    fun `agentic loop overrun triggers across cumulative turns, not per-turn`() {
        // Each turn is 5 + 5 = 10 tokens. Cap is 15. Turn 1 lands at 10
        // (under cap). Turn 2 brings cumulative to 20 (over) — that's where
        // the throw must happen.
        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.ToolCalls(
            listOf(ToolCall(name = "noop", arguments = emptyMap())),
            TokenUsage(5, 5),
        ))
        responses.add(LlmResponse.Text("late", TokenUsage(5, 5)))
        val mock = ModelClient { _ -> responses.removeFirst() }

        val a = agent<String, String>("a") {
            model { ollama("llama3"); client = mock }
            budget { maxTokens = 15 }
            tools { tool("noop", "") { _ -> "ok" } }
            skills { skill<String, String>("s", "s") { tools("noop") } }
        }

        val ex = assertThrows<BudgetExceededException> { a("input") }
        assertEquals(BudgetReason.TOKENS, ex.reason)
    }

    @Test
    fun `loop with null tokenUsage on responses ignores the token cap entirely`() {
        // Provider doesn't report token usage. The loop must not accumulate
        // anything (a null is not zero) and the cap effectively does nothing —
        // matching the "best-effort" contract documented on BudgetConfig.
        // If the implementation accidentally treated null as zero, no cap
        // would fire either; the key assertion is that the loop completes
        // normally rather than tripping a phantom budget.
        val mock = ModelClient { _ -> LlmResponse.Text("done") }  // no usage

        val a = agent<String, String>("a") {
            model { ollama("llama3"); client = mock }
            budget { maxTokens = 1 }  // hyper-tight cap; null usage means it must not fire
            skills { skill<String, String>("s", "s") { tools() } }
        }

        assertEquals("done", a("input"))
    }
}
