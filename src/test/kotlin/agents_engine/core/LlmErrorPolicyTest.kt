package agents_engine.core

import agents_engine.model.LlmErrorDecision
import agents_engine.model.LlmMessage
import agents_engine.model.ModelClient
import org.junit.jupiter.api.assertThrows
import java.net.ConnectException
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * #3508 — the LLM-error policy: when a model is configured, a model-call failure (incl. a *down*
 * server surfacing as a raw `ConnectException`) fails fast and loud by default, normalized to
 * [LlmProviderException]; `onLLMError` opts into recovery. When no model is set, no call is made and
 * no LLM error can arise.
 */
class LlmErrorPolicyTest {

    /** Simulates a down server: the SAM `chat` throws a raw transport error on every path. */
    private val downServer = ModelClient { _: List<LlmMessage> ->
        throw ConnectException("Connection refused")
    }

    private fun agenticAgent(block: Agent<String, String>.() -> Unit = {}) =
        agent<String, String>("policy") {
            model { ollama("m"); client = downServer }
            skills { skill<String, String>("s", "does a thing") { tools() } }
            block()
        }

    @Test
    fun `a down model fails fast and loud, preserving the original transport error`() {
        val a = agenticAgent()
        // The real ConnectException propagates unchanged (the LlmCallFailure marker is unwrapped at
        // the chokepoint) — fail fast and loud, identity preserved.
        val ex = assertThrows<ConnectException> { a("hi") }
        assertEquals("Connection refused", ex.message)
    }

    @Test
    fun `onLLMError RespondWith recovers the loop with a typed fallback`() {
        val a = agenticAgent {
            onLLMError { LlmErrorDecision.RespondWith("fallback") }
        }
        assertEquals("fallback", a("hi"))
    }

    @Test
    fun `onLLMError Rethrow keeps the loud default`() {
        val a = agenticAgent {
            onLLMError { LlmErrorDecision.Rethrow }
        }
        assertThrows<ConnectException> { a("hi") }
    }

    @Test
    fun `onLLMError receives the original transport error, not a wrapper`() {
        var seen: Throwable? = null
        val a = agenticAgent {
            onLLMError { e -> seen = e; LlmErrorDecision.RespondWith("ok") }
        }
        a("hi")
        assertEquals(ConnectException::class, seen!!::class)
    }

    @Test
    fun `a routing-time model failure fails loud (onLLMError covers the agentic loop in v1)`() {
        // Two compatible agentic skills + a model → resolution routes via the LLM, whose chat throws.
        // v1 scopes onLLMError recovery to the loop; a routing-time failure still propagates loud.
        val a = agent<String, String>("router") {
            model { ollama("m"); client = downServer }
            skills {
                skill<String, String>("a", "first") { tools() }
                skill<String, String>("b", "second") { tools() }
            }
            onLLMError { LlmErrorDecision.RespondWith("routed-fallback") }
        }
        assertThrows<ConnectException> { a("hi") }
    }

    @Test
    fun `no model configured runs the implementedBy skill without any LLM call`() {
        val deterministic = agent<String, String>("det") {
            skills { skill<String, String>("echo", "echo") { implementedBy { it.uppercase() } } }
        }
        // No model, no LLM error possible — the hardcoded logic runs.
        assertEquals("HI", deterministic("hi"))
    }
}
