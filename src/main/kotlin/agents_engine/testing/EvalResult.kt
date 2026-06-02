package agents_engine.testing

/** Result of running an [EvalCase] against an agent. */
data class EvalResult<OUT>(
    val caseName: String,
    val output: OUT?,
    val outcomes: List<EvalOutcome>,
    val invocationError: Throwable?,
    /**
     * #2494 — advisory LLM judge verdicts keyed by the label passed to
     * `judge(label, rubric)`. Empty when no judges are registered.
     * NOT considered by [passed] or [failureMessage] — judges are
     * advisory; deterministic expectations are the gating contract.
     */
    val judgeVerdicts: Map<String, JudgeOutcome> = emptyMap(),
) {
    val passed: Boolean get() = invocationError == null && outcomes.all { it.passed }

    val failureMessage: String?
        get() = when {
            passed -> null
            invocationError != null ->
                "eval case \"$caseName\" failed: agent threw ${invocationError::class.simpleName}: ${invocationError.message}"
            else -> {
                val fails = outcomes.filterNot { it.passed }
                "eval case \"$caseName\" failed: ${fails.joinToString("\n") { "  - ${it.label}: ${it.failureDetail}" }}"
            }
        }

    /**
     * #2494 — multi-line summary of advisory judge verdicts. Format is
     * one line per judge: `[advisory] <label>: <score>/<max> — <rationale>`.
     * Empty string when no judges ran. Marked clearly as advisory so
     * report consumers don't confuse judges with the deterministic
     * pass/fail contract.
     */
    val judgeSummary: String
        get() = judgeVerdicts.entries.joinToString("\n") { (label, outcome) ->
            when (outcome) {
                is JudgeOutcome.Scored -> "[advisory] $label: ${outcome.verdict.score} — ${outcome.verdict.rationale}"
                is JudgeOutcome.Errored -> "[advisory] $label: <judge error: ${outcome.errorDetail}>"
            }
        }
}
