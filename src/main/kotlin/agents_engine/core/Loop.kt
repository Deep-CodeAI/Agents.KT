package agents_engine.core

class Loop<IN, OUT>(
    private val execution: (IN) -> OUT,
    private val next: (OUT) -> IN?,
) {
    operator fun invoke(input: IN): OUT {
        var current = execution(input)
        while (true) {
            val feedback = next(current) ?: return current
            current = execution(feedback)
        }
    }
}

fun <A, B> Agent<A, B>.loop(next: (B) -> A?): Loop<A, B> {
    this.markPlaced("loop")
    return Loop(execution = { input -> this(input) }, next = next)
}

fun <A, B> Pipeline<A, B>.loop(next: (B) -> A?): Loop<A, B> =
    Loop(execution = { input -> this(input) }, next = next)

infix fun <A, B, C> Agent<A, B>.then(other: Loop<B, C>): Pipeline<A, C> {
    this.markPlaced("pipeline")
    return Pipeline(listOf(this)) { input -> other(this(input)) }
}

infix fun <A, B, C> Pipeline<A, B>.then(other: Loop<B, C>): Pipeline<A, C> =
    Pipeline(agents) { input -> other(this(input)) }

infix fun <A, B, C> Loop<A, B>.then(other: Agent<B, C>): Pipeline<A, C> {
    other.markPlaced("pipeline")
    return Pipeline(listOf(other)) { input -> other(this(input)) }
}

infix fun <A, B, C> Loop<A, B>.then(other: Pipeline<B, C>): Pipeline<A, C> =
    Pipeline(other.agents) { input -> other(this(input)) }
