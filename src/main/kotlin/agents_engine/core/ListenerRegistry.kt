package agents_engine.core

import agents_engine.model.BudgetReason
import agents_engine.model.TokenUsage
import agents_engine.runtime.events.AgentEvent

/**
 * #2793 — the observability listener slots + `fire*` dispatch extracted out of the [Agent] god class.
 * Holds every listener channel the agent surfaces (tool use / denial / hallucination, approvals,
 * knowledge, skill choice, router rationale, errors, budget threshold + budget-exceeded decisions,
 * plus the multi-subscriber token-usage and agent-event streams). Multi-subscriber dispatch runs
 * through [dispatchSafely] so a throwing telemetry subscriber can never break an agent run.
 *
 * [Agent] keeps the public `onX` DSL setters (delegating writes here) and the runtime reads these
 * slots via `agent.listeners.<slot>` — no public-API change. The slots are settable post-construction
 * by design (instrumentation / tracing), matching the pre-extraction contract.
 */
internal class ListenerRegistry {

    // Single-slot observability listeners (most-recently-registered wins).
    var toolUseListener: ((name: String, args: Map<String, Any?>, result: Any?) -> Unit)? = null
    var toolDeniedListener: ((name: String, args: Map<String, Any?>, reason: String) -> Unit)? = null
    var toolHallucinatedListener:
        ((name: String, args: Map<String, Any?>, allowedTools: List<String>) -> Unit)? = null
    var approvalRequestedListener: ((title: String, hasBody: Boolean, timeoutMs: Long?) -> Unit)? = null
    var approvalDecidedListener: ((decision: String, hasPayload: Boolean) -> Unit)? = null
    var knowledgeUsedListener: ((name: String, content: String) -> Unit)? = null
    var skillChosenListener: ((name: String) -> Unit)? = null
    var routerRationaleListener: ((rationale: String) -> Unit)? = null
    var errorListener: ((Throwable) -> Unit)? = null
    var budgetThresholdListener: ((reason: BudgetReason, usedPercent: Double) -> Unit)? = null

    /**
     * Hard-cap decision hook (#2412). Unlike the others this is NOT fire-and-forget — its return
     * drives control flow (Extend vs Stop), so the runtime invokes it directly and its exceptions are
     * not swallowed. Kept here as the home of all listener slots.
     */
    var budgetExceededListener:
        ((reason: BudgetReason, currentLimit: Int) -> agents_engine.model.BudgetDecision)? = null

    // Multi-subscriber streams.
    private val tokenUsageListeners = mutableListOf<(TokenUsage) -> Unit>()
    private val agentEventListeners = mutableListOf<(AgentEvent<*>) -> Unit>()

    val tokenUsageListenerCount: Int get() = tokenUsageListeners.size

    fun addTokenUsageListener(block: (TokenUsage) -> Unit) { tokenUsageListeners += block }
    fun addAgentEventListener(block: (AgentEvent<*>) -> Unit) { agentEventListeners += block }

    fun fireTokenUsage(usage: TokenUsage) {
        tokenUsageListeners.toList().forEach { listener ->
            dispatchSafely("onTokenUsage listener") { listener(usage) }
        }
    }

    fun fireAgentEvent(event: AgentEvent<*>) {
        agentEventListeners.toList().forEach { listener ->
            dispatchSafely("onAgentEvent listener") { listener(event) }
        }
    }
}
