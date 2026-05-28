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
     * #2420 — single-checkpoint resume for Branch. The source agent runs
     * deterministically and produces a routing key; the matched route's
     * executor runs once on that key. If the route crashes mid-run, we
     * don't want to re-execute the source — its output is the only thing
     * that needs to survive the boundary.
     *
     * v1 contract:
     * - Only `String`-typed sources are persisted (the route's executor
     *   stays typed as before; only the source output crosses the
     *   snapshot boundary as a `String`).
     * - The route is picked anew on resume by re-running [matchRoute] on
     *   the persisted output. Routes are deterministic in input, so the
     *   same input always picks the same route — there's no need to
     *   persist the route identity itself.
     * - The route's own output is not snapshotted; if the route returns
     *   successfully there's nothing left to resume.
     */
    suspend fun resumeOrStart(
        sessionId: String,
        input: IN,
        store: agents_engine.core.CompositionSnapshotStore,
    ): OUT {
        val seed = store.load(sessionId)
        val sourceOutput: Any? = if (seed != null && seed.stageIndex >= 1) {
            // Source already completed in an earlier attempt; reuse its output.
            seed.intermediate
        } else {
            val produced = source.invokeSuspend(input)
            store.save(
                sessionId,
                agents_engine.core.CompositionSnapshot(
                    sessionId = sessionId,
                    stageIndex = 1,
                    intermediate = produced?.toString() ?: "",
                ),
            )
            produced
        }

        // Same route-matching logic as invokeSuspend, but driven off the
        // (possibly restored) source output.
        if (sourceOutput == null) {
            val nullRoute = routes.firstOrNull { it is BranchRoute.NullRoute }
                ?: routes.firstOrNull { it is BranchRoute.ElseRoute }
                ?: error(
                    "Branch source produced null and no onNull or onElse clause was declared."
                )
            return nullRoute.executor(null)
        }
        for (route in routes) {
            when (route) {
                is BranchRoute.TypeRoute -> if (route.klass.isInstance(sourceOutput)) return route.executor(sourceOutput)
                is BranchRoute.NullRoute -> { /* skipped for non-null */ }
                is BranchRoute.ElseRoute -> return route.executor(sourceOutput)
            }
        }
        error("No branch defined for ${sourceOutput::class.simpleName} (and no onElse clause).")
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
