package agents_engine.composition.firstof

import agents_engine.core.Agent
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * `agents_engine/composition/firstof/FirstOf.kt` — #3869. Speculative
 * execution: race N branches against the same input, return the **first
 * successful** result, cancel every loser.
 * LLM latency is variance-dominated — racing identical (or equivalent)
 * agents trades tokens for a large p99 win.
 *
 * ```kotlin
 * val fast = firstOf(primary, fallbackProvider)   // distinct agents
 * val sampled = generator.speculative(3)          // same agent, 3 racers
 * fast("question")                                // first success wins
 * ```
 *
 * Semantics:
 * - First branch to complete **successfully** wins; the race returns at
 *   the winner's latency. Losers are cancelled but NOT awaited (the
 *   sacrificial-worker precedent of blocking tools): a suspending loser
 *   stops promptly, a blocking body may finish in the background with
 *   its result discarded.
 * - A branch failing does NOT settle the race — the others keep running;
 *   only when ALL branches fail does the race throw (the last failure).
 * - `onRaceSettled { winner, cancelled, elapsedMillis -> }` is the audit
 *   signal naming who won and who was cancelled.
 *
 * **Budget honesty:** losers' tokens up to the cancellation point are
 * real spend at the provider even though their results are dropped.
 * Per-branch budgets apply individually; cross-branch aggregation of
 * cancelled partial usage is a known gap tracked on #3869 — cap
 * worst-case spend by bounding N.
 */
class FirstOf<IN, OUT> internal constructor(
    val agents: List<Agent<*, *>>,
    internal val branchNames: List<String>,
    /** One racer per entry, aligned with [branchNames]; self-speculation repeats the same agent. */
    internal val branchAgents: List<Agent<IN, OUT>>,
    internal val executions: List<suspend (IN) -> OUT>,
) {
    init {
        require(executions.isNotEmpty()) { "firstOf requires at least one branch." }
    }

    internal var raceListener: ((winner: String, cancelled: List<String>, elapsedMillis: Long) -> Unit)? = null

    /** #3869 — audit signal: who won, who was cancelled, how long the race took. */
    fun onRaceSettled(block: (winner: String, cancelled: List<String>, elapsedMillis: Long) -> Unit): FirstOf<IN, OUT> {
        raceListener = block
        return this
    }

    operator fun invoke(input: IN): OUT = runBlocking { invokeSuspend(input) }

    suspend fun invokeSuspend(input: IN): OUT = race(input, executions)

    /**
     * #3869/#3866 — emitter-aware race: every racer streams its events into
     * [emitter] (losers' streams stop at cancellation). Shared by
     * `firstOf.session(input)`.
     */
    internal suspend fun sessionInvoke(
        input: IN,
        emitter: agents_engine.model.AgentEventEmitter,
        onWinner: (String) -> Unit = {},
    ): OUT {
        val streaming: List<suspend (IN) -> OUT> = branchAgents.map { a ->
            { value: IN ->
                @Suppress("UNCHECKED_CAST")
                agents_engine.runtime.events.runAgentInSession(
                    a as Agent<Any?, Any?>,
                    value,
                    emitter,
                ).first as OUT
            }
        }
        return race(input, streaming, onWinner)
    }

    private suspend fun race(
        input: IN,
        racers: List<suspend (IN) -> OUT>,
        onWinner: (String) -> Unit = {},
    ): OUT {
        val startedAt = System.nanoTime()
        // The race returns at the WINNER's latency: losers are cancelled but
        // not awaited — same precedent as blocking tools' sacrificial worker
        // threads. A cooperative (suspending) loser stops promptly; a
        // non-cooperative blocking body may run to completion in the
        // background with its result discarded. Caller cancellation and
        // failures cancel the whole race via the finally below.
        val raceJob = kotlinx.coroutines.SupervisorJob()
        val raceScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default + raceJob)
        val results = Channel<Pair<Int, Result<OUT>>>(racers.size)
        try {
            racers.forEachIndexed { index, exec ->
                raceScope.launch { results.trySend(index to runCatching { exec(input) }) }
            }
            var lastFailure: Throwable? = null
            repeat(racers.size) {
                val (index, result) = results.receive()
                result.fold(
                    onSuccess = { winner ->
                        onWinner(branchNames[index])
                        raceListener?.invoke(
                            branchNames[index],
                            branchNames.filterIndexed { i, _ -> i != index },
                            (System.nanoTime() - startedAt) / NANOS_PER_MILLI,
                        )
                        return winner
                    },
                    onFailure = { lastFailure = it },
                )
            }
            throw lastFailure ?: error("firstOf race finished with no results")
        } finally {
            raceJob.cancel()
        }
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}

/**
 * Race distinct agents; each is single-placement-marked. For racing the
 * SAME agent against itself use [speculative].
 */
fun <IN, OUT> firstOf(vararg agents: Agent<IN, OUT>): FirstOf<IN, OUT> {
    require(agents.size > 1) { "firstOf needs at least two branches; got ${agents.size}." }
    agents.forEach { it.markPlaced("firstOf") }
    return FirstOf(
        agents = agents.toList(),
        branchNames = agents.map { it.name },
        branchAgents = agents.toList(),
        executions = agents.map { a -> { input: IN -> a.invokeSuspend(input) } },
    )
}

/**
 * Self-speculation: race [n] concurrent invocations of this agent against
 * the same input; first success wins, the rest are cancelled. The agent
 * is placement-marked once (it is one component raced n times).
 */
fun <IN, OUT> Agent<IN, OUT>.speculative(n: Int): FirstOf<IN, OUT> {
    require(n > 1) { "speculative(n) needs n > 1; got $n." }
    this.markPlaced("firstOf")
    val self = this
    return FirstOf(
        agents = listOf(self),
        branchNames = List(n) { "${self.name}#${it + 1}" },
        branchAgents = List(n) { self },
        executions = List(n) { { input: IN -> self.invokeSuspend(input) } },
    )
}
