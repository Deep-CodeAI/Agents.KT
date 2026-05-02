package agents_engine.composition.branch

import agents_engine.core.*
import kotlinx.coroutines.runBlocking
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
    data class TypeRoute<OUT>(val klass: KClass<*>, override val executor: suspend (Any?) -> OUT) : BranchRoute<OUT>
    data class NullRoute<OUT>(override val executor: suspend (Any?) -> OUT) : BranchRoute<OUT>
    data class ElseRoute<OUT>(override val executor: suspend (Any?) -> OUT) : BranchRoute<OUT>
}

class Branch<IN, OUT> internal constructor(
    private val source: Agent<IN, *>,
    private val routes: List<BranchRoute<OUT>>,
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
}
