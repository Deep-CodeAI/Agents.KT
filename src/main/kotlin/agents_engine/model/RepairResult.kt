package agents_engine.model

/**
 * Outcome of an `onError { }` repair handler ([OnErrorBuilder]): the loop applies a [Fixed] value,
 * [Retry]s up to a cap, surfaces an [Escalated] failure, or gives up as [Unrecoverable].
 */
sealed interface RepairResult {
    data class Fixed(val value: String) : RepairResult
    data class Retry(val maxAttempts: Int) : RepairResult
    data class Escalated(val reason: String, val severity: Severity) : RepairResult
    data object Unrecoverable : RepairResult
}
