package agents_engine.core

/**
 * #2793 — the before-interceptor subsystem extracted out of the [Agent] god class. Holds the three
 * before-interceptor lists (#1907), the `onInterceptorDecision` observers, and the decision plumbing:
 * each `decideBeforeX` runs the shared [runDecisionChain] fold (first non-`Proceed` wins, `ProceedWith`
 * threads its replacement, a throwing interceptor becomes a [Decision.Deny]) and then fires the
 * decision to observers under a swallow-and-log policy so instrumentation can never break a run.
 *
 * [Agent] keeps the public `onBeforeX` / `onInterceptorDecision` DSL setters and the
 * `beforeXInterceptorCount` readers, delegating to this collaborator — no public-API change.
 */
internal class InterceptorChain {

    private val beforeSkill = mutableListOf<(String) -> Decision<String>>()
    private val beforeToolCall =
        mutableListOf<(name: String, args: Map<String, Any?>) -> Decision<Map<String, Any?>>>()
    private val beforeTurn = mutableListOf<(List<ChatMessage>) -> Decision<List<ChatMessage>>>()
    private val decisionListeners = mutableListOf<(InterceptorPoint, Decision<*>) -> Unit>()

    val beforeSkillCount: Int get() = beforeSkill.size
    val beforeToolCallCount: Int get() = beforeToolCall.size
    val beforeTurnCount: Int get() = beforeTurn.size

    fun addBeforeSkill(block: (skillName: String) -> Decision<String>) { beforeSkill += block }
    fun addBeforeToolCall(block: (name: String, args: Map<String, Any?>) -> Decision<Map<String, Any?>>) {
        beforeToolCall += block
    }
    fun addBeforeTurn(block: (messages: List<ChatMessage>) -> Decision<List<ChatMessage>>) {
        beforeTurn += block
    }
    fun addDecisionListener(block: (point: InterceptorPoint, decision: Decision<*>) -> Unit) {
        decisionListeners += block
    }

    fun decideBeforeSkill(skillName: String): Decision<String> {
        val interceptors = beforeSkill.toList()
        val decision = runDecisionChain(skillName, interceptors)
        fire(InterceptorPoint.BeforeSkill, decision, interceptors.isNotEmpty())
        return decision
    }

    fun decideBeforeTurn(messages: List<ChatMessage>): Decision<List<ChatMessage>> {
        val interceptors = beforeTurn.toList()
        val decision = runDecisionChain(messages, interceptors)
        fire(InterceptorPoint.BeforeTurn, decision, interceptors.isNotEmpty())
        return decision
    }

    /**
     * [policyGate] is the pre-evaluated declared-policy decision (#1916 Layer 1) — non-null only when a
     * tool's [agents_engine.model.ToolPolicy] denies these args. The gate runs *before* user
     * interceptors and short-circuits the chain (matching "first non-Proceed wins"), but is still
     * surfaced to the decision observers. The user-interceptor fold reuses [runDecisionChain] by
     * binding `name` so the two-arg interceptors present the same single-arg shape as the others —
     * removing the hand-inlined duplicate fold (and its second `@Suppress`).
     */
    fun decideBeforeToolCall(
        name: String,
        args: Map<String, Any?>,
        policyGate: Decision.Deny?,
    ): Decision<Map<String, Any?>> {
        if (policyGate != null) {
            fire(InterceptorPoint.BeforeToolCall, policyGate, hasInterceptors = true)
            return policyGate
        }
        val interceptors = beforeToolCall.toList()
        val bound = interceptors.map { ic -> { boundArgs: Map<String, Any?> -> ic(name, boundArgs) } }
        val decision = runDecisionChain(args, bound)
        fire(InterceptorPoint.BeforeToolCall, decision, interceptors.isNotEmpty())
        return decision
    }

    private fun fire(point: InterceptorPoint, decision: Decision<*>, hasInterceptors: Boolean) {
        if (!hasInterceptors) return
        decisionListeners.toList().forEach { listener ->
            dispatchSafely("onInterceptorDecision listener") { listener(point, decision) }
        }
    }
}
