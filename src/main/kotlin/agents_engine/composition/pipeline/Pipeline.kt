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
    private val execution: suspend (IN) -> OUT,
) {
    operator fun invoke(input: IN): OUT = runBlocking { execution(input) }

    suspend fun invokeSuspend(input: IN): OUT = execution(input)
}

infix fun <A, B, C> Agent<A, B>.then(other: Agent<B, C>): Pipeline<A, C> {
    this.markPlaced("pipeline")
    other.markPlaced("pipeline")
    return Pipeline(listOf(this, other)) { input -> other.invokeSuspend(this.invokeSuspend(input)) }
}

infix fun <A, B, C> Pipeline<A, B>.then(other: Agent<B, C>): Pipeline<A, C> {
    other.markPlaced("pipeline")
    return Pipeline(agents + other) { input -> other.invokeSuspend(this.invokeSuspend(input)) }
}

infix fun <A, B, C> Agent<A, B>.then(other: Forum<B, C>): Pipeline<A, C> {
    this.markPlaced("pipeline")
    return Pipeline(listOf(this) + other.agents) { input -> other.invokeSuspend(this.invokeSuspend(input)) }
}

infix fun <A, B, C> Pipeline<A, B>.then(other: Forum<B, C>): Pipeline<A, C> {
    return Pipeline(agents + other.agents) { input -> other.invokeSuspend(this.invokeSuspend(input)) }
}

infix fun <A, B, C> Pipeline<A, B>.then(other: Pipeline<B, C>): Pipeline<A, C> {
    return Pipeline(agents + other.agents) { input -> other.invokeSuspend(this.invokeSuspend(input)) }
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
