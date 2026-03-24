package agents_engine.composition.parallel

import agents_engine.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking

class Parallel<IN, OUT>(
    val agents: List<Agent<*, *>>,
    internal val executions: List<(IN) -> OUT>,
) {
    operator fun invoke(input: IN): List<OUT> = runBlocking(Dispatchers.Default) {
        executions.map { exec -> async { exec(input) } }.map { it.await() }
    }
}

operator fun <A, B> Agent<A, B>.div(other: Agent<A, B>): Parallel<A, B> {
    this.markPlaced("parallel")
    other.markPlaced("parallel")
    return Parallel(
        agents = listOf(this, other),
        executions = listOf({ input -> this(input) }, { input -> other(input) }),
    )
}

operator fun <A, B> Parallel<A, B>.div(other: Agent<A, B>): Parallel<A, B> {
    other.markPlaced("parallel")
    return Parallel(
        agents = agents + other,
        executions = executions + { input -> other(input) },
    )
}
