package agents_engine.core

class Forum<IN, OUT>(
    val agents: List<Agent<*, *>>,
)

operator fun <A, B, C> Agent<A, B>.times(other: Agent<*, C>): Forum<A, C> {
    this.markPlaced("forum")
    other.markPlaced("forum")
    return Forum(listOf(this, other))
}

operator fun <A, B, C> Forum<A, B>.times(other: Agent<*, C>): Forum<A, C> {
    other.markPlaced("forum")
    return Forum(agents + other)
}
