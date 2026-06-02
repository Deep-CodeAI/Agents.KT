package agents_engine.testing

/** Outcome of a single expectation in an eval case. */
data class EvalOutcome(
    val label: String,
    val passed: Boolean,
    val failureDetail: String?,
)
