package agents_engine.composition.loop

import agents_engine.composition.pipeline.Pipeline
import agents_engine.core.Agent

/**
 * `agents_engine/composition/loop/LoopBuilder.kt` — #3870. The named
 * reflexion / evaluator-optimizer shape: re-run until a predicate (or an
 * `evalGate` judge) approves the output.
 *
 * ```kotlin
 * val gate = evalGate(qualityRubric, threshold = 7)
 * val refiner = drafter.loopUntil(maxIterations = 5) { draft -> gate.pass(draft) }
 *
 * val poller = checker.loopUntil(
 *     maxIterations = 10,
 *     feedback = { it.retryRequest },        // OUT -> next IN; omit when IN == OUT
 * ) { it.status == Done }
 * ```
 *
 * Sugar over `loop(maxIterations) { next }`: the loop re-runs while the
 * predicate is false, feeding `feedback(out)` (or the output itself when
 * `IN == OUT`) as the next input. `maxIterations` still throws on
 * overrun — a predicate that never fires is a bug, not an infinite loop.
 *
 * Named `loopUntil` (not a `loop { until { } }` DSL block) so the
 * existing `loop { next }` trailing-lambda overload stays unambiguous.
 */
fun <A, B> Agent<A, B>.loopUntil(
    maxIterations: Int = DEFAULT_MAX_ITERATIONS,
    feedback: ((B) -> A)? = null,
    predicate: (B) -> Boolean,
): Loop<A, B> = this.loop(maxIterations = maxIterations, next = untilNext(predicate, feedback))

/** #3870 — `loopUntil` over a pipeline. */
fun <A, B> Pipeline<A, B>.loopUntil(
    maxIterations: Int = DEFAULT_MAX_ITERATIONS,
    feedback: ((B) -> A)? = null,
    predicate: (B) -> Boolean,
): Loop<A, B> = this.loop(maxIterations = maxIterations, next = untilNext(predicate, feedback))

private fun <A, B> untilNext(predicate: (B) -> Boolean, feedback: ((B) -> A)?): (B) -> A? = { out ->
    if (predicate(out)) {
        null
    } else {
        @Suppress("UNCHECKED_CAST")
        (feedback?.invoke(out) ?: out as A)
    }
}
