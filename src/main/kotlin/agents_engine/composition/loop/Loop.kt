package agents_engine.composition.loop

import agents_engine.core.*
import agents_engine.composition.pipeline.Pipeline
import kotlinx.coroutines.runBlocking

private const val DEFAULT_MAX_ITERATIONS = 1_000

/**
 * #638: `execution` is suspend so the loop body composes cleanly with other suspending
 * operators. The user-supplied `next` callback stays sync — feedback functions are
 * pure logic; if a future use case needs suspending feedback the callback type can
 * widen without breaking callers.
 */
class Loop<IN, OUT>(
    private val execution: suspend (IN) -> OUT,
    private val next: (OUT) -> IN?,
    private val maxIterations: Int = DEFAULT_MAX_ITERATIONS,
) {
    init {
        require(maxIterations > 0) { "Loop maxIterations must be greater than 0." }
    }

    operator fun invoke(input: IN): OUT = runBlocking { invokeSuspend(input) }

    suspend fun invokeSuspend(input: IN): OUT {
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
    return Loop(execution = { input -> this.invokeSuspend(input) }, next = next, maxIterations = maxIterations)
}

fun <A, B> Pipeline<A, B>.loop(
    maxIterations: Int = DEFAULT_MAX_ITERATIONS,
    next: (B) -> A?,
): Loop<A, B> =
    Loop(execution = { input -> this.invokeSuspend(input) }, next = next, maxIterations = maxIterations)
