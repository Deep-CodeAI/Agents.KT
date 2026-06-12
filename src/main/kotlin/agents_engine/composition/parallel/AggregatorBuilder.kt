package agents_engine.composition.parallel

import agents_engine.core.Agent

/**
 * #3872 — strategy factory for `parallel.aggregate { … }`. Each method
 * returns the [Aggregator] the block should end with:
 *
 * ```kotlin
 * (a / b / c).aggregate { majorityVote() }
 * (a / b / c).aggregate { selectByMax { it.confidence } }
 * (a / b / c).aggregate { bestOfN { judge(it).score } }
 * (a / b / c).aggregate { weighted(mapOf(a to 2.0, b to 1.0, c to 1.0)) }
 * ```
 */
class AggregatorBuilder<OUT> @PublishedApi internal constructor(
    private val branchAgents: List<Agent<*, *>>,
) {
    /**
     * The most frequent value wins (`equals`-based). Ties break
     * deterministically: the candidate that appeared first in branch
     * order wins.
     */
    fun majorityVote(): Aggregator<OUT> = Aggregator("majorityVote") { outputs ->
        requireResults(outputs)
        outputs.groupBy { it }
            .maxWith(compareBy({ it.value.size }, { -outputs.indexOf(it.key) }))
            .key
    }

    /** The output with the largest [selector] value wins; first max wins ties. */
    fun <C : Comparable<C>> selectByMax(selector: (OUT) -> C): Aggregator<OUT> =
        Aggregator("selectByMax") { outputs ->
            requireResults(outputs)
            outputs.maxByOrNull(selector) ?: error("unreachable: outputs verified non-empty")
        }

    /**
     * Score every output with [scorer] (e.g. a judge agent invoked
     * blocking) and keep the highest; first max wins ties. Scorer cost is
     * the caller's — each branch output is scored exactly once.
     */
    fun bestOfN(scorer: (OUT) -> Double): Aggregator<OUT> = Aggregator("bestOfN") { outputs ->
        requireResults(outputs)
        outputs.maxByOrNull(scorer) ?: error("unreachable: outputs verified non-empty")
    }

    /**
     * Weighted majority: each branch's vote counts with its agent's weight
     * (missing agents default to 1.0). The value with the largest summed
     * weight wins; ties break by first appearance in branch order.
     */
    fun weighted(weights: Map<Agent<*, *>, Double>): Aggregator<OUT> = Aggregator("weighted") { outputs ->
        requireResults(outputs)
        require(outputs.size == branchAgents.size) {
            "weighted() needs one output per branch (${branchAgents.size}); got ${outputs.size}."
        }
        val scores = LinkedHashMap<OUT, Double>()
        outputs.forEachIndexed { index, output ->
            val weight = weights[branchAgents[index]] ?: DEFAULT_WEIGHT
            scores[output] = (scores[output] ?: 0.0) + weight
        }
        scores.maxWith(compareBy { it.value }).key
    }

    private fun requireResults(outputs: List<OUT>) {
        check(outputs.isNotEmpty()) {
            "Aggregation received no branch outputs — every parallel branch failed or produced nothing."
        }
    }

    private companion object {
        const val DEFAULT_WEIGHT = 1.0
    }
}
