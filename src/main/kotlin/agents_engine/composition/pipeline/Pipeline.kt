package agents_engine.composition.pipeline

import agents_engine.core.*
import agents_engine.composition.branch.Branch
import agents_engine.composition.forum.Forum
import agents_engine.composition.loop.Loop
import agents_engine.composition.parallel.Parallel
import kotlinx.coroutines.runBlocking

/**
 * #638: `execution` is suspend so internal cross-calls between Pipeline / Forum /
 * Parallel / Loop / Branch can chain in one coroutine — no nested `runBlocking`.
 * The blocking [invoke] is a one-line shim that wraps `runBlocking` exactly once,
 * at the user-visible call boundary.
 */
class Pipeline<IN, OUT>(
    val agents: List<Agent<*, *>>,
    /**
     * #1745 — session-aware execution path. When `pipeline.session(input)`
     * is called, this runs instead of [execution] with a non-null emitter,
     * surfacing inner-agent events with their own `agentId`s. Defaults to
     * the non-streaming `execution` so any `then` overload that hasn't been
     * converted yet still works (Pipeline session emits only the terminal
     * Completed, no inner events — known gap, see #1745 follow-ups).
     *
     * Declared BEFORE [execution] so the trailing-lambda construction
     * `Pipeline(agents) { ... }` still binds the lambda to [execution].
     */
    internal val sessionExec: (suspend (
        input: IN,
        emitter: agents_engine.model.AgentEventEmitter,
    ) -> OUT)? = null,
    private val execution: suspend (IN) -> OUT,
) {
    /** Effective sessionExec: explicit when supplied, otherwise falls back to execution (no events). */
    internal val effectiveSessionExec: suspend (
        input: IN,
        emitter: agents_engine.model.AgentEventEmitter,
    ) -> OUT = sessionExec ?: { input, _ -> execution(input) }

    operator fun invoke(input: IN): OUT = runBlocking { execution(input) }

    suspend fun invokeSuspend(input: IN): OUT = execution(input)
}

infix fun <A, B, C> Agent<A, B>.then(other: Agent<B, C>): Pipeline<A, C> {
    this.markPlaced("pipeline")
    other.markPlaced("pipeline")
    val first = this
    return Pipeline(
        agents = listOf(first, other),
        // #1745: streaming path runs both agents through runAgentInSession
        // so events from both flow into the emitter with their own agentIds.
        sessionExec = { input, emitter ->
            val (mid, _) = agents_engine.runtime.events.runAgentInSession(first, input, emitter)
            val (out, _) = agents_engine.runtime.events.runAgentInSession(other, mid, emitter)
            out
        },
        execution = { input -> other.invokeSuspend(first.invokeSuspend(input)) },
    )
}

infix fun <A, B, C> Pipeline<A, B>.then(other: Agent<B, C>): Pipeline<A, C> {
    other.markPlaced("pipeline")
    val inner = this
    return Pipeline(
        agents = inner.agents + other,
        // #1746: chain the inner Pipeline's streaming output into the new
        // Agent's session run. Inner Pipeline's effectiveSessionExec
        // forwards events from each of its stages; runAgentInSession then
        // emits the trailing Agent's bracket events.
        sessionExec = { input, emitter ->
            val mid = inner.effectiveSessionExec(input, emitter)
            val (out, _) = agents_engine.runtime.events.runAgentInSession(other, mid, emitter)
            out
        },
        execution = { input -> other.invokeSuspend(inner.invokeSuspend(input)) },
    )
}

infix fun <A, B, C> Agent<A, B>.then(other: Forum<B, C>): Pipeline<A, C> {
    this.markPlaced("pipeline")
    return Pipeline(listOf(this) + other.agents) { input -> other.invokeSuspend(this.invokeSuspend(input)) }
}

infix fun <A, B, C> Pipeline<A, B>.then(other: Forum<B, C>): Pipeline<A, C> {
    return Pipeline(agents + other.agents) { input -> other.invokeSuspend(this.invokeSuspend(input)) }
}

infix fun <A, B, C> Pipeline<A, B>.then(other: Pipeline<B, C>): Pipeline<A, C> {
    val left = this
    return Pipeline(
        agents = left.agents + other.agents,
        // #1746: chain both pipelines' streaming exec paths.
        sessionExec = { input, emitter ->
            val mid = left.effectiveSessionExec(input, emitter)
            other.effectiveSessionExec(mid, emitter)
        },
        execution = { input -> other.invokeSuspend(left.invokeSuspend(input)) },
    )
}

infix fun <A, B, C> Agent<A, B>.then(other: Parallel<B, C>): Pipeline<A, List<C>> {
    this.markPlaced("pipeline")
    return Pipeline(listOf(this) + other.agents) { input -> other.invokeSuspend(this.invokeSuspend(input)) }
}

infix fun <A, B, C> Pipeline<A, B>.then(other: Parallel<B, C>): Pipeline<A, List<C>> {
    return Pipeline(agents + other.agents) { input -> other.invokeSuspend(this.invokeSuspend(input)) }
}

infix fun <A, B, C> Parallel<A, B>.then(other: Agent<List<B>, C>): Pipeline<A, C> {
    other.markPlaced("pipeline")
    return Pipeline(agents + other) { input -> other.invokeSuspend(this.invokeSuspend(input)) }
}

infix fun <A, B, C> Parallel<A, B>.then(other: Pipeline<List<B>, C>): Pipeline<A, C> {
    return Pipeline(agents + other.agents) { input -> other.invokeSuspend(this.invokeSuspend(input)) }
}

infix fun <A, B, C> Agent<A, B>.then(other: Loop<B, C>): Pipeline<A, C> {
    this.markPlaced("pipeline")
    return Pipeline(listOf(this)) { input -> other.invokeSuspend(this.invokeSuspend(input)) }
}

infix fun <A, B, C> Pipeline<A, B>.then(other: Loop<B, C>): Pipeline<A, C> =
    Pipeline(agents) { input -> other.invokeSuspend(this.invokeSuspend(input)) }

infix fun <A, B, C> Loop<A, B>.then(other: Agent<B, C>): Pipeline<A, C> {
    other.markPlaced("pipeline")
    return Pipeline(listOf(other)) { input -> other.invokeSuspend(this.invokeSuspend(input)) }
}

infix fun <A, B, C> Loop<A, B>.then(other: Pipeline<B, C>): Pipeline<A, C> =
    Pipeline(other.agents) { input -> other.invokeSuspend(this.invokeSuspend(input)) }

infix fun <A, B, C> Agent<A, B>.then(other: Branch<B, C>): Pipeline<A, C> {
    this.markPlaced("pipeline")
    return Pipeline(listOf(this)) { input -> other.invokeSuspend(this.invokeSuspend(input)) }
}

infix fun <A, B, C> Pipeline<A, B>.then(other: Branch<B, C>): Pipeline<A, C> =
    Pipeline(agents) { input -> other.invokeSuspend(this.invokeSuspend(input)) }

infix fun <A, B, C> Branch<A, B>.then(other: Agent<B, C>): Pipeline<A, C> {
    other.markPlaced("pipeline")
    return Pipeline(listOf(other)) { input -> other.invokeSuspend(this.invokeSuspend(input)) }
}

infix fun <A, B, C> Branch<A, B>.then(other: Pipeline<B, C>): Pipeline<A, C> =
    Pipeline(other.agents) { input -> other.invokeSuspend(this.invokeSuspend(input)) }
