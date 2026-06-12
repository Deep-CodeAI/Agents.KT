package agents_engine.testing

/**
 * #3876 — result of running one [EvalSuite] across several models.
 * The interesting signal is [divergent]: cases that pass on some models
 * and fail on others — behavioral drift the per-model pass/fail totals
 * hide. [toMarkdown] renders the case × model matrix for CI artifacts /
 * PR comments.
 */
data class CrossModelEvalResult<OUT>(
    val suiteName: String,
    /** Per-model suite results, keyed by the caller's model label, in declaration order. */
    val byModel: Map<String, EvalSuiteResult<OUT>>,
) {
    /** True when every case passed on every model. */
    val allPassed: Boolean get() = byModel.values.all { it.passed }

    /** Case names with mixed outcomes across models — the regression signal. */
    val divergent: List<String>
        get() = caseNames().filter { case ->
            val outcomes = byModel.values.mapNotNull { suite ->
                suite.results.firstOrNull { it.caseName == case }?.passed
            }.toSet()
            outcomes.size > 1
        }

    fun toMarkdown(): String = buildString {
        appendLine("## Eval suite `$suiteName` across ${byModel.size} models")
        appendLine()
        append("| case |")
        byModel.keys.forEach { append(" $it |") }
        appendLine()
        append("|---|")
        repeat(byModel.size) { append("---|") }
        appendLine()
        caseNames().forEach { case ->
            append("| $case${if (case in divergent) " ⚠️" else ""} |")
            byModel.values.forEach { suite ->
                val result = suite.results.firstOrNull { it.caseName == case }
                append(" ${if (result?.passed == true) "✅" else "❌"} |")
            }
            appendLine()
        }
        appendLine()
        appendLine(
            if (divergent.isEmpty()) {
                "No cross-model divergence."
            } else {
                "**Divergent cases (behavioral drift): ${divergent.joinToString()}**"
            },
        )
    }.trimEnd()

    private fun caseNames(): List<String> =
        byModel.values.firstOrNull()?.results?.map { it.caseName } ?: emptyList()
}
