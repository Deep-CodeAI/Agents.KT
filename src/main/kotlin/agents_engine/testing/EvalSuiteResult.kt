package agents_engine.testing

/** Result of running an [EvalSuite]. */
data class EvalSuiteResult<OUT>(
    val name: String,
    val results: List<EvalResult<OUT>>,
) {
    val passed: Boolean get() = results.all { it.passed }
    val failureSummary: String?
        get() = if (passed) null else results
            .filterNot { it.passed }
            .joinToString("\n") { it.failureMessage ?: "(unknown failure in ${it.caseName})" }
}
