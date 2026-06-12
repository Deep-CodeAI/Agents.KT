package agents_engine.testing

/**
 * `agents_engine/testing/EvalGate.kt` — #3870. A pass/fail gate over an
 * LLM-as-judge rubric, built for `loop { until { } }` exit conditions
 * (reflexion / evaluator-optimizer):
 *
 * ```kotlin
 * val gate = evalGate(qualityRubric, threshold = 7)
 * val refiner = drafter.loop {
 *     maxIterations = 5
 *     until { draft -> gate.pass(draft) }
 * }
 * ```
 *
 * Each `pass(...)` call runs one judge-model scoring pass (cost: one
 * model call per iteration — use a cheap pinned judge model, or
 * [DeterministicModelClient] in tests). [lastVerdict] keeps the most
 * recent rationale for logging/debugging the loop's exit.
 */
class EvalGate internal constructor(
    private val rubric: JudgeRubric,
    private val threshold: Int,
) {
    @Volatile
    var lastVerdict: JudgeVerdict? = null
        private set

    /** True when the judge scores [candidate] at or above the threshold. */
    fun pass(candidate: Any?): Boolean = verdict(candidate).score >= threshold

    /** Full verdict (score + rationale) for [candidate]. */
    fun verdict(candidate: Any?): JudgeVerdict =
        LlmJudge(rubric).score(input = null, output = candidate).also { lastVerdict = it }
}

/**
 * #3870 — gate factory. [threshold] is on the rubric's own
 * [JudgeRubric.scoreRange] scale and must lie within it.
 */
fun evalGate(rubric: JudgeRubric, threshold: Int = DEFAULT_EVAL_GATE_THRESHOLD): EvalGate {
    require(threshold in rubric.scoreRange) {
        "threshold $threshold is outside the rubric's scoreRange ${rubric.scoreRange}."
    }
    return EvalGate(rubric, threshold)
}

const val DEFAULT_EVAL_GATE_THRESHOLD: Int = 7
