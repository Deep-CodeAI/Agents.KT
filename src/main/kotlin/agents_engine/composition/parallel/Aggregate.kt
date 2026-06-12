package agents_engine.composition.parallel

import agents_engine.composition.pipeline.Pipeline
import agents_engine.composition.pipeline.then
import agents_engine.core.agent

/**
 * `agents_engine/composition/parallel/Aggregate.kt` — #3872. One-line
 * ensemble patterns over a parallel fan-out:
 *
 * ```kotlin
 * val ensemble = (a / b / c).aggregate { majorityVote() }
 * // Pipeline<IN, OUT> — composes, streams, audits like any pipeline
 * ```
 *
 * Pure compositional sugar: builds a deterministic reducer agent named
 * `aggregate-<strategy>` and returns `fanOut then reducer` — so the
 * aggregation shows up in audit/streaming as that agent's
 * `SkillStarted` / `SkillCompleted` events with the strategy in the
 * name, and the result composes onward like any `Pipeline`.
 *
 * Failure semantics: if every branch fails, the parallel stage itself
 * fails (structured concurrency) before the reducer runs; on the
 * streaming path that surfaces as the session's terminal `Failed`.
 */
inline fun <IN, reified OUT : Any> Parallel<IN, OUT>.aggregate(
    crossinline block: AggregatorBuilder<OUT>.() -> Aggregator<OUT>,
): Pipeline<IN, OUT> {
    val aggregator = AggregatorBuilder<OUT>(agents).block()
    val reducer = agent<List<OUT>, OUT>("aggregate-${aggregator.strategy}") {
        skills {
            skill<List<OUT>, OUT>(
                aggregator.strategy,
                "Aggregates ${agents.size} parallel branch outputs via ${aggregator.strategy}",
            ) {
                implementedBy { outputs -> aggregator.reduce(outputs) }
            }
        }
    }
    return this then reducer
}
