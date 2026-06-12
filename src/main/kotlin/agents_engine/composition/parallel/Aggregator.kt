package agents_engine.composition.parallel

/**
 * #3872 — a named reduction over a parallel fan-out's `List<OUT>`.
 * Built via [AggregatorBuilder] inside `parallel.aggregate { … }`; the
 * [strategy] name becomes part of the reducer agent's name so audit
 * events carry which aggregation ran.
 */
class Aggregator<OUT> @PublishedApi internal constructor(
    val strategy: String,
    @PublishedApi internal val reduce: (List<OUT>) -> OUT,
)
