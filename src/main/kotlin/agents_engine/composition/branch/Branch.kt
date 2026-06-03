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
        // #2802 — one ordered-match implementation. The non-streaming path now delegates to
        // [matchRoute] exactly like the streaming `session` path, instead of re-deriving the loop.
        val route = matchRoute(result) ?: noMatchError(result)
        return route.executor(result)
    }

    private fun noMatchError(result: Any?): Nothing =
        if (result == null) {
            error(
                "Branch source produced null and no onNull or onElse clause was declared. " +
                    "Add `onNull then ...` or `onElse then ...` to handle this case."
            )
        } else {
            error("No branch defined for ${result::class.simpleName} (and no onElse clause).")
        }

    /**
     * #1748 — picks the matching route for [result] using documented order/precedence: a null result
     * prefers an explicit `onNull` then falls back to `onElse`; a non-null result takes the first
     * `onType` whose class matches, else `onElse`. Returns null if nothing matches (caller errors).
     */
    internal fun matchRoute(result: Any?): BranchRoute<OUT>? {
        if (result == null) {
            return routes.firstOrNull { it is BranchRoute.NullRoute }
                ?: routes.firstOrNull { it is BranchRoute.ElseRoute }
        }
        // #2802 — classify each route for a non-null result; NullRoute simply never matches here
        // (handled above), replacing the dead empty exhaustiveness arm with a real `false`.
        return routes.firstOrNull { route ->
            when (route) {
                is BranchRoute.TypeRoute -> route.klass.isInstance(result)
                is BranchRoute.ElseRoute -> true
                is BranchRoute.NullRoute -> false
            }
        }
    }
}
