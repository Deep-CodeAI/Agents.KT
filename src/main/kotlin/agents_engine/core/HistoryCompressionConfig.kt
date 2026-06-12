package agents_engine.core

/**
 * #3865 Phase 1 — resolved history-compression settings. Built via
 * [HistoryCompressionBuilder] in `agent { historyCompression { … } }`.
 * The summarizer config (not the dynamic summaries) is what belongs in
 * the manifest — compression never changes the agent's capability shape.
 */
class HistoryCompressionConfig internal constructor(
    /** When true for the current history, compression runs before the turn. */
    val triggerWhen: (List<ChatMessage>) -> Boolean,
    /** Most recent N messages are never compressed. */
    val preserveRecent: Int,
    /** Turns the compressed middle into a digest (Summarize strategy). Deterministic by default. */
    val summarizer: (List<ChatMessage>) -> String,
    /** #4492 — what the compressed middle becomes; see [CompactionStrategy]. */
    val strategy: CompactionStrategy = CompactionStrategy.Summarize(),
)
