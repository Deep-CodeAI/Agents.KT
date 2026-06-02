package agents_engine.composition.branch

import agents_engine.core.*
import kotlinx.coroutines.runBlocking

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

class Branch<IN, OUT> internal constructor(
    val source: Agent<IN, *>,
    val routes: List<BranchRoute<OUT>>,
) {
    val agents: List<Agent<*, *>>
        get() = (listOf(source) + routes.flatMap { it.targetAgents }).distinct()

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
