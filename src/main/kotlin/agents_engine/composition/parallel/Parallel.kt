package agents_engine.composition.parallel

import agents_engine.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * `agents_engine/composition/parallel/Parallel.kt` — concurrent fan-out
 * via the `/` operator: `agentA / agentB / agentC` produces a
 * `Parallel<IN, OUT>` whose `invoke(input)` returns `List<OUT>` from
 * running all branches concurrently. Each branch must share the same
 * `IN` and `OUT`. Sessions: per-branch session-aware execution streams
 * each branch's events with its own `agentId` (#1750). Runs in
 * `coroutineScope` (no nested runBlocking) — cancellation, timeouts,
 * dispatcher all live with the caller (#638). See
 * `src/main/resources/internals-agent/composition/parallel/Parallel.md`
 * (#1837 / #1871).
 */

/**
 * #638: Parallel runs branches concurrently inside `coroutineScope`, not
 * `runBlocking(Dispatchers.Default)`. The framework no longer creates its own
 * scope — cancellation, timeouts, and dispatcher choice all live with the caller.
 * The blocking [invoke] is a one-line shim that runs `runBlocking` exactly once
 * at the user-visible call boundary.
 */
class Parallel<IN, OUT>(
    val agents: List<Agent<*, *>>,
    internal val executions: List<suspend (IN) -> OUT>,
    /**
     * #1750 — per-branch session-aware execution. When set, each branch's
     * events stream into the shared emitter passed to `Parallel.session`;
     * the events interleave in arrival order but are demultiplexable by
     * `agentId`. Null for branches built outside the div factories —
     * those run via `executions` with no events flowing through.
     */
    internal val sessionExecutions: List<suspend (IN, agents_engine.model.AgentEventEmitter) -> OUT>? = null,
) {
    operator fun invoke(input: IN): List<OUT> = runBlocking { invokeSuspend(input) }

    suspend fun invokeSuspend(input: IN): List<OUT> = withContext(Dispatchers.Default) {
        coroutineScope {
            executions.map { exec -> async { exec(input) } }.map { it.await() }
        }
    }
}

operator fun <A, B> Agent<A, B>.div(other: Agent<A, B>): Parallel<A, B> {
    this.markPlaced("parallel")
    other.markPlaced("parallel")
    val left = this
    return Parallel(
        agents = listOf(left, other),
        executions = listOf(
            { input -> left.invokeSuspend(input) },
            { input -> other.invokeSuspend(input) },
        ),
        // #1750: each branch streams via runAgentInSession; events from
        // both branches flow into the same emitter and interleave.
        sessionExecutions = listOf(
            { input, emitter -> agents_engine.runtime.events.runAgentInSession(left, input, emitter).first },
            { input, emitter -> agents_engine.runtime.events.runAgentInSession(other, input, emitter).first },
        ),
    )
}

operator fun <A, B> Parallel<A, B>.div(other: Agent<A, B>): Parallel<A, B> {
    other.markPlaced("parallel")
    val inner = this
    return Parallel(
        agents = inner.agents + other,
        executions = inner.executions + { input -> other.invokeSuspend(input) },
        // #1750: extend the inner Parallel's session executions with the new branch.
        // If the inner Parallel had no sessionExecutions (built outside the factory),
        // we can't manufacture them retroactively — fall back to null so the whole
        // Parallel emits only the terminal events.
        sessionExecutions = inner.sessionExecutions?.let { execs ->
            execs + { input, emitter ->
                agents_engine.runtime.events.runAgentInSession(other, input, emitter).first
            }
        },
    )
}
