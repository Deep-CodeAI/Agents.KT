package agents_engine.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #2793 — pins the interceptor subsystem extracted out of the Agent god class into [InterceptorChain]:
 * the before-tool-call fold (now reusing [runDecisionChain]), the declared-policy gate short-circuit,
 * and the `onInterceptorDecision` observability firing with its swallow-and-log policy.
 */
class InterceptorChainTest {

    private val noGate: Decision.Deny? = null

    @Test
    fun `no interceptors and no gate proceeds without firing the decision listener`() {
        val chain = InterceptorChain()
        var fired = false
        chain.addDecisionListener { _, _ -> fired = true }
        val decision = chain.decideBeforeToolCall("write", mapOf("p" to 1), noGate)
        assertEquals(Decision.Proceed, decision)
        assertTrue(!fired, "decision listener must not fire when there are no interceptors")
    }

    @Test
    fun `a non-null policy gate short-circuits and is surfaced to the decision listener`() {
        val chain = InterceptorChain()
        var observed: Decision<*>? = null
        chain.addDecisionListener { point, d -> if (point == InterceptorPoint.BeforeToolCall) observed = d }
        val gate = Decision.Deny("outside write glob")
        val decision = chain.decideBeforeToolCall("write", mapOf("p" to 1), gate)
        assertEquals(gate, decision)
        assertEquals(gate, observed, "the gate denial is observable even though no user interceptor ran")
    }

    @Test
    fun `first non-Proceed wins and ProceedWith replacement threads through the chain`() {
        val chain = InterceptorChain()
        chain.addBeforeToolCall { _, args -> Decision.ProceedWith(args + ("seen1" to true)) }
        chain.addBeforeToolCall { _, args ->
            assertTrue(args["seen1"] == true, "second interceptor sees the first's replacement")
            Decision.Deny("blocked by second")
        }
        // ProceedWith wins as the effective decision (first non-Proceed), so the Deny from the
        // second interceptor does NOT override it — matching the shared runDecisionChain semantics.
        val decision = chain.decideBeforeToolCall("write", mapOf("p" to 1), noGate)
        assertTrue(decision is Decision.ProceedWith<*>)
    }

    @Test
    fun `an interceptor that throws is converted to a Deny`() {
        val chain = InterceptorChain()
        chain.addBeforeToolCall { _, _ -> error("boom") }
        val decision = chain.decideBeforeToolCall("write", emptyMap(), noGate)
        assertTrue(decision is Decision.Deny && "boom" in decision.reason)
    }

    @Test
    fun `a throwing decision listener is swallowed and does not break dispatch`() {
        val chain = InterceptorChain()
        chain.addBeforeToolCall { _, _ -> Decision.Deny("no") }
        chain.addDecisionListener { _, _ -> error("listener blew up") }
        var second: Decision<*>? = null
        chain.addDecisionListener { _, d -> second = d }
        val decision = chain.decideBeforeToolCall("write", emptyMap(), noGate)
        assertTrue(decision is Decision.Deny)
        assertTrue(second is Decision.Deny, "a throwing listener must not stop later listeners")
    }

    @Test
    fun `counts reflect registered interceptors`() {
        val chain = InterceptorChain()
        assertEquals(0, chain.beforeToolCallCount)
        chain.addBeforeToolCall { _, args -> Decision.ProceedWith(args) }
        assertEquals(1, chain.beforeToolCallCount)
        assertNull(noGate)
    }
}
