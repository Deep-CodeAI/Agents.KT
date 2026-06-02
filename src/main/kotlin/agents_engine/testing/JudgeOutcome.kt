package agents_engine.testing

/**
 * #2494 — sealed outcome of a single judge invocation. `Scored` carries
 * the typed verdict; `Errored` captures parse failures or
 * out-of-range scores from the judge model. Errors here NEVER gate the
 * case's pass/fail — they just surface in the report.
 */
sealed interface JudgeOutcome {
    data class Scored(val verdict: JudgeVerdict) : JudgeOutcome
    data class Errored(val errorDetail: String) : JudgeOutcome
}
