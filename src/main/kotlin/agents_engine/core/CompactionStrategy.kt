package agents_engine.core

/**
 * #4492 (PRD §5.7.1) — what the compressed middle of the conversation
 * becomes when `historyCompression` triggers:
 *
 * - [Summarize] — the middle collapses into one digest message
 *   (deterministic extractive by default; pass a summarizer for
 *   abstractive). This is the #3865 Phase-1 behavior.
 * - [SlidingWindow] — the middle is dropped entirely, replaced by a
 *   one-line elision marker; zero summarizer cost. `keepRecent`
 *   overrides `preserveRecent`.
 * - [Custom] — full control: the lambda receives the middle and returns
 *   its replacement messages. A thrown exception skips compaction for
 *   that turn (degrade, don't fail — same policy as summarizers).
 */
sealed interface CompactionStrategy {
    /** Digest the middle into one summary message (default). */
    data class Summarize(val summarizer: ((List<ChatMessage>) -> String)? = null) : CompactionStrategy

    /** Drop the middle, keep the most recent [keepRecent] messages + an elision marker. */
    data class SlidingWindow(val keepRecent: Int) : CompactionStrategy {
        init {
            require(keepRecent >= 0) { "keepRecent must be >= 0, was $keepRecent." }
        }
    }

    /** Replace the middle with whatever [compact] returns. */
    data class Custom(val compact: (middle: List<ChatMessage>) -> List<ChatMessage>) : CompactionStrategy
}
