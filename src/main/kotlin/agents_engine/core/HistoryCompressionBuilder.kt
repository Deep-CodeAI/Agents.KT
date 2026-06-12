package agents_engine.core

/** #3865 Phase 1 — DSL builder for `agent { historyCompression { … } }`. */
class HistoryCompressionBuilder {
    /** Default trigger: compress when the history exceeds this many messages. */
    var triggerMessages: Int = DEFAULT_TRIGGER_MESSAGES

    /** Most recent N messages stay untouched (extended backward past orphaned tool results). */
    var preserveRecent: Int = DEFAULT_PRESERVE_RECENT

    /**
     * #4492 — compaction strategy: `Summarize` (default), `SlidingWindow(keepRecent)`
     * (drop the middle, zero summarizer cost; overrides [preserveRecent]), or
     * `Custom { middle -> replacement }`.
     */
    var strategy: CompactionStrategy = CompactionStrategy.Summarize()

    private var trigger: ((List<ChatMessage>) -> Boolean)? = null
    private var summarizer: (List<ChatMessage>) -> String = ::extractiveDigest

    /**
     * Custom trigger predicate — overrides [triggerMessages]. Keep it
     * deterministic for replayability (message counts, content sizes —
     * not wall clock).
     */
    fun triggerWhen(predicate: (List<ChatMessage>) -> Boolean) {
        trigger = predicate
    }

    /**
     * Custom summarizer over the messages being compressed. The default is
     * a deterministic extractive digest (no LLM call); pass a lambda that
     * invokes a cheap model if you want abstractive summaries — a thrown
     * exception skips compression for that turn, it never fails the run.
     */
    fun summarizer(block: (List<ChatMessage>) -> String) {
        summarizer = block
        strategy = CompactionStrategy.Summarize(block)
    }

    internal fun build(): HistoryCompressionConfig {
        require(triggerMessages > 1) { "triggerMessages must be > 1, was $triggerMessages." }
        require(preserveRecent >= 0) { "preserveRecent must be >= 0, was $preserveRecent." }
        val effectivePreserve = (strategy as? CompactionStrategy.SlidingWindow)?.keepRecent ?: preserveRecent
        return HistoryCompressionConfig(
            triggerWhen = trigger ?: { messages -> messages.size > triggerMessages },
            preserveRecent = effectivePreserve,
            summarizer = (strategy as? CompactionStrategy.Summarize)?.summarizer ?: summarizer,
            strategy = strategy,
        )
    }

    companion object {
        const val DEFAULT_TRIGGER_MESSAGES: Int = 40
        const val DEFAULT_PRESERVE_RECENT: Int = 4
    }
}
