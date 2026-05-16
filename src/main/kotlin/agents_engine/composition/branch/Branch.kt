package agents_engine.composition.branch

import agents_engine.core.*
import kotlinx.coroutines.runBlocking
import kotlin.reflect.KClass

/**
 * `agents_engine/composition/branch/Branch.kt` — the routing operator.
 * A `Branch<IN, OUT>` runs a source agent, dispatches on the result
 * type/null/else to a registered route, and returns the route's
 * `OUT`. Order matters — first matching route wins (#638). Suspend
 * executors so routes can dispatch into agents/pipelines via their
 * suspending entry points without nested runBlocking. See
 * `src/main/resources/internals-agent/composition/branch/Branch.md`
 * (#1837 / #1864).
 */

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
    data class TypeRoute<OUT>(
        val klass: KClass<*>,
        override val executor: suspend (Any?) -> OUT,
        override val sessionExecutor: (suspend (Any?, agents_engine.model.AgentEventEmitter) -> OUT)? = null,
        override val routedAgentName: String? = null,
    ) : BranchRoute<OUT>
    data class NullRoute<OUT>(
        override val executor: suspend (Any?) -> OUT,
        override val sessionExecutor: (suspend (Any?, agents_engine.model.AgentEventEmitter) -> OUT)? = null,
        override val routedAgentName: String? = null,
    ) : BranchRoute<OUT>
    data class ElseRoute<OUT>(
        override val executor: suspend (Any?) -> OUT,
        override val sessionExecutor: (suspend (Any?, agents_engine.model.AgentEventEmitter) -> OUT)? = null,
        override val routedAgentName: String? = null,
    ) : BranchRoute<OUT>
}

class Branch<IN, OUT> internal constructor(
    internal val source: Agent<IN, *>,
    internal val routes: List<BranchRoute<OUT>>,
) {
    operator fun invoke(input: IN): OUT = runBlocking { invokeSuspend(input) }

    suspend fun invokeSuspend(input: IN): OUT {
        val result: Any? = source.invokeSuspend(input)
        if (result == null) {
            val nullRoute = routes.firstOrNull { it is BranchRoute.NullRoute }
                ?: routes.firstOrNull { it is BranchRoute.ElseRoute }
                ?: error(
                    "Branch source produced null and no onNull or onElse clause was declared. " +
                        "Add `onNull then ...` or `onElse then ...` to handle this case."
                )
            return nullRoute.executor(null)
        }
        for (route in routes) {
            when (route) {
                is BranchRoute.TypeRoute -> if (route.klass.isInstance(result)) return route.executor(result)
                is BranchRoute.NullRoute -> { /* skipped for non-null */ }
                is BranchRoute.ElseRoute -> return route.executor(result)
            }
        }
        error("No branch defined for ${result::class.simpleName} (and no onElse clause).")
    }

    /**
     * #1748 — picks the matching route for [result] using the same order/precedence
     * as [invokeSuspend]. Returns null if no route matches (caller can `error()`).
     */
    internal fun matchRoute(result: Any?): BranchRoute<OUT>? {
        if (result == null) {
            return routes.firstOrNull { it is BranchRoute.NullRoute }
                ?: routes.firstOrNull { it is BranchRoute.ElseRoute }
        }
        for (route in routes) {
            when (route) {
                is BranchRoute.TypeRoute -> if (route.klass.isInstance(result)) return route
                is BranchRoute.NullRoute -> { /* skipped */ }
                is BranchRoute.ElseRoute -> return route
            }
        }
        return null
    }
}
