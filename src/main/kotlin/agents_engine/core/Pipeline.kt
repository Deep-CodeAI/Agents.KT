package agents_engine.core

class Pipeline<IN, OUT>(
    val agents: List<Agent<*, *>>,
)

infix fun <A, B, C> Agent<A, B>.then(other: Agent<B, C>): Pipeline<A, C> {
    this.markPlaced("pipeline")
    other.markPlaced("pipeline")
    return Pipeline(listOf(this, other))
}

infix fun <A, B, C> Pipeline<A, B>.then(other: Agent<B, C>): Pipeline<A, C> {
    other.markPlaced("pipeline")
    return Pipeline(agents + other)
}

infix fun <A, B, C> Agent<A, B>.then(other: Forum<B, C>): Pipeline<A, C> {
    this.markPlaced("pipeline")
    return Pipeline(listOf(this) + other.agents)
}

infix fun <A, B, C> Pipeline<A, B>.then(other: Forum<B, C>): Pipeline<A, C> {
    return Pipeline(agents + other.agents)
}

infix fun <A, B, C> Pipeline<A, B>.then(other: Pipeline<B, C>): Pipeline<A, C> {
    return Pipeline(agents + other.agents)
}

infix fun <A, B, C> Agent<A, B>.then(other: Parallel<B, C>): Pipeline<A, List<C>> {
    this.markPlaced("pipeline")
    return Pipeline(listOf(this) + other.agents)
}

infix fun <A, B, C> Pipeline<A, B>.then(other: Parallel<B, C>): Pipeline<A, List<C>> {
    return Pipeline(agents + other.agents)
}

infix fun <A, B, C> Parallel<A, B>.then(other: Agent<List<B>, C>): Pipeline<A, C> {
    other.markPlaced("pipeline")
    return Pipeline(agents + other)
}

infix fun <A, B, C> Parallel<A, B>.then(other: Pipeline<List<B>, C>): Pipeline<A, C> {
    return Pipeline(agents + other.agents)
}
