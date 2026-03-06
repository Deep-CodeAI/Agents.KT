package agents_engine.core

class Parallel<IN, OUT>(
    val agents: List<Agent<*, *>>,
)

operator fun <A, B> Agent<A, B>.div(other: Agent<A, B>): Parallel<A, B> {
    this.markPlaced("parallel")
    other.markPlaced("parallel")
    return Parallel(listOf(this, other))
}

operator fun <A, B> Parallel<A, B>.div(other: Agent<A, B>): Parallel<A, B> {
    other.markPlaced("parallel")
    return Parallel(agents + other)
}
