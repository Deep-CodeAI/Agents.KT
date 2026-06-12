package agents_engine.core

/** #3865 — outcome handed to the `onHistoryCompressed` listener / `HistoryCompressed` event. */
data class HistoryCompressionResult(
    val replacedCount: Int,
    val preservedCount: Int,
    val digest: String,
)
