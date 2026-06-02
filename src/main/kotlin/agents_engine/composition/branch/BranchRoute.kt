package agents_engine.composition.branch

import agents_engine.core.*
import kotlin.reflect.KClass

/**
 * One branch route. Order matters: routes are evaluated in registration order
 * and the first matcher whose predicate returns true wins. Place specific routes
 * before general ones (e.g., `on<Dog>()` before `on<Animal>()`).
 *
 * - `TypeRoute(klass)`: matches via `klass.isInstance(result)` — covers subtypes.
 * - `NullRoute`: matches when the result is `null`.
 * - `ElseRoute`: matches anything not handled by earlier routes.
 *
 * #638: executors are suspend so a branch can dispatch into agents/pipelines via
 * their suspending entry points without nested `runBlocking`.
 */
sealed interface BranchRoute<OUT> {
    val executor: suspend (Any?) -> OUT
    /**
     * #1748 — session-aware executor used when the branch runs inside a
     * `branch.session(input)` call. Null when the route was constructed
     * outside `BranchBuilder` (the regular `executor` wins; routed events
     * don't flow through, only the source agent's events do).
     */
    val sessionExecutor: (suspend (Any?, agents_engine.model.AgentEventEmitter) -> OUT)?
    /**
     * #1748 — name of the agent (or last agent in the routed pipeline)
     * whose output becomes the Branch's output. Used as `agentId` for
     * the terminal `AgentEvent.Completed`. Null when the route was
     * constructed outside `BranchBuilder` — in that case the terminal
     * Completed falls back to the source agent's name.
     */
    val routedAgentName: String?
    val targetAgents: List<Agent<*, *>>
    data class TypeRoute<OUT>(
        val klass: KClass<*>,
        override val executor: suspend (Any?) -> OUT,
        override val sessionExecutor: (suspend (Any?, agents_engine.model.AgentEventEmitter) -> OUT)? = null,
        override val routedAgentName: String? = null,
        override val targetAgents: List<Agent<*, *>> = emptyList(),
    ) : BranchRoute<OUT>
    data class NullRoute<OUT>(
        override val executor: suspend (Any?) -> OUT,
        override val sessionExecutor: (suspend (Any?, agents_engine.model.AgentEventEmitter) -> OUT)? = null,
        override val routedAgentName: String? = null,
        override val targetAgents: List<Agent<*, *>> = emptyList(),
    ) : BranchRoute<OUT>
    data class ElseRoute<OUT>(
        override val executor: suspend (Any?) -> OUT,
        override val sessionExecutor: (suspend (Any?, agents_engine.model.AgentEventEmitter) -> OUT)? = null,
        override val routedAgentName: String? = null,
        override val targetAgents: List<Agent<*, *>> = emptyList(),
    ) : BranchRoute<OUT>
}
