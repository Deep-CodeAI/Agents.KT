package agents_engine.composition.parallel

import agents_engine.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

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
    return Parallel(
        agents = listOf(this, other),
        executions = listOf(
            { input -> this.invokeSuspend(input) },
            { input -> other.invokeSuspend(input) },
        ),
    )
}

operator fun <A, B> Parallel<A, B>.div(other: Agent<A, B>): Parallel<A, B> {
    other.markPlaced("parallel")
    return Parallel(
        agents = agents + other,
        executions = executions + { input -> other.invokeSuspend(input) },
    )
}
