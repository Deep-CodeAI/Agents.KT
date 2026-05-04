package agents_engine.model

import agents_engine.core.agent
import org.junit.jupiter.api.assertThrows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// Tests for #962 — onError is the infrastructure-error observability hook.
// It MUST fire when an exception is about to propagate out of an agentic
// invocation, and the original exception MUST always rethrow afterward —
// onError is observability, never recovery (that's onToolError's job).
class OnErrorListenerTest {

    @Test
    fun `onError fires when ModelClient throws`() {
        val captured = mutableListOf<Throwable>()
        val mock = ModelClient { _ -> throw RuntimeException("transport blew up") }

        val a = agent<String, String>("a") {
            model { ollama("llama3"); client = mock }
            skills { skill<String, String>("s", "s") { tools() } }
            onError { captured += it }
        }

        val thrown = assertThrows<RuntimeException> { a("input") }
        // (Coroutines stack-trace recovery clones exceptions across
        // dispatcher boundaries, so we assert on logical identity —
        // class + message — rather than reference identity. Both the
        // listener and the caller see logically the same exception.)
        assertEquals("transport blew up", thrown.message)
        assertEquals(1, captured.size)
        val seen = captured.single()
        assertTrue(seen is RuntimeException)
        assertEquals("transport blew up", seen.message)
    }

    @Test
    fun `onError fires when LLM output cannot be parsed as agent OUT type`() {
        // Agent declares OUT = Int, model returns text that cannot become an Int.
        val mock = ModelClient { _ -> LlmResponse.Text("not-a-number") }

        val captured = mutableListOf<Throwable>()
        val a = agent<String, Int>("a") {
            model { ollama("llama3"); client = mock }
            skills { skill<String, Int>("s", "s") { tools() } }
            onError { captured += it }
        }

        assertThrows<Throwable> { a("input") }
        assertEquals(1, captured.size)
        // Sanity: the captured throwable's message should mention the parse failure.
        val msg = captured.single().message.orEmpty()
        assertTrue(msg.contains("parse", ignoreCase = true) || msg.contains("Int"))
    }

    @Test
    fun `onError fires on BudgetExceededException`() {
        // Model never returns Text — every response is a tool call into a no-op
        // tool. With maxTurns = 1, the second turn trips the budget.
        val responses = ArrayDeque<LlmResponse>()
        repeat(8) {
            responses.add(LlmResponse.ToolCalls(listOf(ToolCall(name = "noop", arguments = emptyMap()))))
        }
        val mock = ModelClient { _ -> responses.removeFirst() }

        val captured = mutableListOf<Throwable>()
        val a = agent<String, String>("a") {
            lateinit var noop: Tool<Map<String, Any?>, Any?>
            model { ollama("llama3"); client = mock }
            budget { maxTurns = 1 }
            tools { noop = tool("noop", "") { _ -> "ok" } }
            skills { skill<String, String>("s", "s") { tools(noop) } }
            onError { captured += it }
        }

        assertThrows<BudgetExceededException> { a("input") }
        assertEquals(1, captured.size)
        val captured0 = captured.single()
        assertTrue(captured0 is BudgetExceededException)
        assertEquals(BudgetReason.TURNS, captured0.reason)
    }

    @Test
    fun `onError absent — no callback, original error still propagates`() {
        // Agent declares no onError listener. The original exception must
        // still reach the caller unchanged; the absence of a listener must
        // not introduce any swallowing.
        val boom = IllegalStateException("nope")
        val mock = ModelClient { _ -> throw boom }

        val a = agent<String, String>("a") {
            model { ollama("llama3"); client = mock }
            skills { skill<String, String>("s", "s") { tools() } }
        }

        val thrown = assertThrows<IllegalStateException> { a("input") }
        assertEquals("nope", thrown.message)
    }

    @Test
    fun `listener exception does not swallow the original error`() {
        val mock = ModelClient { _ -> throw RuntimeException("real failure") }
        val listenerError = IllegalStateException("listener itself blew up")

        var listenerFired = false
        val a = agent<String, String>("a") {
            model { ollama("llama3"); client = mock }
            skills { skill<String, String>("s", "s") { tools() } }
            onError {
                listenerFired = true
                throw listenerError
            }
        }

        val thrown = assertThrows<RuntimeException> { a("input") }
        // The original message — not the listener's — is what surfaces.
        // (If the listener's exception had swallowed the original, the
        // caller would see "listener itself blew up" instead.)
        assertEquals("real failure", thrown.message)
        assertTrue(listenerFired)
        // Listener's failure is attached to the propagated exception as a
        // suppressed entry, so it's never silently lost.
        val suppressed = thrown.suppressed.toList()
        assertEquals(1, suppressed.size)
        val attached = suppressed.single()
        assertTrue(attached is IllegalStateException)
        assertEquals("listener itself blew up", attached.message)
    }

    @Test
    fun `onError fires only once per invocation`() {
        // Sanity: the wrapper is around invokeSuspend, not around inner
        // helpers. A single failing chat call → exactly one fire.
        val mock = ModelClient { _ -> throw RuntimeException("once") }

        var fireCount = 0
        val a = agent<String, String>("a") {
            model { ollama("llama3"); client = mock }
            skills { skill<String, String>("s", "s") { tools() } }
            onError { fireCount++ }
        }

        assertThrows<RuntimeException> { a("input") }
        assertEquals(1, fireCount)
    }

    @Test
    fun `onError listener is mutable post-construction (instrumentation use case)`() {
        // The other listeners (onToolUse, onSkillChosen, onKnowledgeUsed) are
        // intentionally settable post-construction for tracing instrumentation.
        // onError follows the same convention — frozen state must not block it.
        val mock = ModelClient { _ -> throw RuntimeException("infra") }

        val a = agent<String, String>("a") {
            model { ollama("llama3"); client = mock }
            skills { skill<String, String>("s", "s") { tools() } }
        }
        // Agent is now validated/frozen.

        var captured: Throwable? = null
        a.onError { captured = it }

        assertThrows<RuntimeException> { a("input") }
        assertNotNull(captured)
    }
}
