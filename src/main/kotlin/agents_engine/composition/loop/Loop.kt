package agents_engine.composition.loop

import agents_engine.core.*
import agents_engine.composition.pipeline.Pipeline

private const val DEFAULT_MAX_ITERATIONS = 1_000

class Loop<IN, OUT>(
    private val execution: (IN) -> OUT,
    private val next: (OUT) -> IN?,
    private val maxIterations: Int = DEFAULT_MAX_ITERATIONS,
) {
    init {
        require(maxIterations > 0) { "Loop maxIterations must be greater than 0." }
    }

    operator fun invoke(input: IN): OUT {
        var current = execution(input)
        var iterations = 1
        while (true) {
            val feedback = next(current) ?: return current
            check(iterations < maxIterations) {
                "Loop exceeded maxIterations=$maxIterations without termination."
            }
            current = execution(feedback)
            iterations++
        }
    }
}

fun <A, B> Agent<A, B>.loop(
    maxIterations: Int = DEFAULT_MAX_ITERATIONS,
    next: (B) -> A?,
): Loop<A, B> {
    this.markPlaced("loop")
    return Loop(execution = { input -> this(input) }, next = next, maxIterations = maxIterations)
}

fun <A, B> Pipeline<A, B>.loop(
    maxIterations: Int = DEFAULT_MAX_ITERATIONS,
    next: (B) -> A?,
): Loop<A, B> =
    Loop(execution = { input -> this(input) }, next = next, maxIterations = maxIterations)
