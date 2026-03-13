package agents_engine.composition.loop

import agents_engine.core.*
import agents_engine.composition.pipeline.Pipeline

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
