package agents_engine.composition.forum

import agents_engine.core.Agent
import agents_engine.core.agent

/**
 * `agents_engine/composition/forum/ForumCaptains.kt` — #3877. Named
 * built-in captains: deterministic transcript captains that aggregate
 * the participants' outputs with a documented strategy. Register via
 * `transcriptCaptain(...)`; the strategy name is the captain's agent
 * name, so audit/streaming events carry which aggregation decided the
 * verdict — the same precedent as `aggregate-<strategy>` (#3872).
 *
 * ```kotlin
 * forum<Question, Answer> {
 *     participant(expert1); participant(expert2); participant(expert3)
 *     transcriptCaptain(consensusCaptain(quorum = 2))
 *     // or: transcriptCaptain(weightedCaptain(mapOf("expert1" to 3.0)))
 *     // or (numeric verdicts): transcriptCaptain(byzantineCaptain())
 * }
 * ```
 */

/**
 * Requires at least [quorum] participants to produce the **same** output
 * (`equals`-based). Fails loud with the full tally when no value reaches
 * quorum — a forum that cannot agree should not silently pick a winner.
 */
inline fun <IN, reified OUT : Any> consensusCaptain(quorum: Int): Agent<ForumTranscript<IN>, OUT> {
    require(quorum > 0) { "quorum must be positive, was $quorum." }
    return agent("consensus-captain") {
        skills {
            skill<ForumTranscript<IN>, OUT>("consensus", "Requires $quorum identical member verdicts") {
                implementedBy { transcript ->
                    val tally = transcript.contributions
                        .map { contributionAs<OUT>(it.output, it.agentName) }
                        .groupingBy { it }
                        .eachCount()
                    val winner = tally.entries.filter { it.value >= quorum }.maxByOrNull { it.value }
                    winner?.key ?: error(
                        "Consensus failed: no verdict reached quorum=$quorum. Tally: $tally",
                    )
                }
            }
        }
    }
}

/**
 * Weighted vote keyed by participant **name**; missing names default to
 * weight 1.0. The verdict with the largest summed weight wins; ties
 * break by first appearance in contribution order.
 */
inline fun <IN, reified OUT : Any> weightedCaptain(
    weights: Map<String, Double>,
): Agent<ForumTranscript<IN>, OUT> = agent("weighted-captain") {
    skills {
        skill<ForumTranscript<IN>, OUT>("weighted-vote", "Weights member verdicts by panelist") {
            implementedBy { transcript ->
                val scores = LinkedHashMap<OUT, Double>()
                transcript.contributions.forEach { contribution ->
                    val verdict = contributionAs<OUT>(contribution.output, contribution.agentName)
                    val weight = weights[contribution.agentName] ?: 1.0
                    scores[verdict] = (scores[verdict] ?: 0.0) + weight
                }
                check(scores.isNotEmpty()) { "weightedCaptain received no contributions." }
                scores.maxWith(compareBy { it.value }).key
            }
        }
    }
}

/**
 * Byzantine-robust captain for **numeric** verdicts: returns the median
 * of the members' `Double` outputs — the 1-dimensional geometric median,
 * robust to up to ⌈n/2⌉−1 adversarial outliers. Vector Krum /
 * Weiszfeld-iteration geometric median for embedding outputs is a
 * tracked follow-up on #3877.
 */
fun <IN> byzantineCaptain(): Agent<ForumTranscript<IN>, Double> = agent("byzantine-captain") {
    skills {
        skill<ForumTranscript<IN>, Double>("robust-median", "Median of numeric member verdicts") {
            implementedBy { transcript ->
                val values = transcript.contributions.map {
                    (it.output as? Number)?.toDouble()
                        ?: error("byzantineCaptain needs numeric verdicts; \"${it.agentName}\" produced ${it.output}")
                }.sorted()
                check(values.isNotEmpty()) { "byzantineCaptain received no contributions." }
                val mid = values.size / 2
                if (values.size % 2 == 1) values[mid] else (values[mid - 1] + values[mid]) / 2.0
            }
        }
    }
}

/** Cast a contribution to the verdict type with a participant-named error. */
@PublishedApi
internal inline fun <reified OUT : Any> contributionAs(output: Any?, participant: String): OUT =
    output as? OUT ?: error(
        "Participant \"$participant\" produced ${output?.let { it::class.simpleName } ?: "null"}, " +
            "expected ${OUT::class.simpleName}.",
    )
