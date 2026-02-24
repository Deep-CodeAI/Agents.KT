package agents_engine.core

class Pipeline<IN, OUT>(
    val agents: List<Agent<*, *>>,
)

operator fun <A, B, C> Agent<A, B>.plus(other: Agent<B, C>): Pipeline<A, C> {
    return Pipeline(listOf(this, other))
}

operator fun <A, B, C> Pipeline<A, B>.plus(other: Agent<B, C>): Pipeline<A, C> {
    return Pipeline(agents + other)
}
