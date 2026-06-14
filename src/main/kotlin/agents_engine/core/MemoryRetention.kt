package agents_engine.core

/**
 * #4515 (PRD §8.5) — retention strategy for a [MemoryBank] slot: what to keep when the stored
 * content would otherwise grow unbounded. Applied on every write. The historical `maxLines` cap is
 * just [Sliding]; [TokenBudget] / [Summarized] / [Unbounded] are the new strategies from the PRD's
 * memory table.
 *
 * (The typed multi-namespace DSL — `memory { sliding<ConversationTurn>(20) }` — is a follow-up; this
 * is the strategy core, configurable per bank via `MemoryBank(retention = …)`.)
 */
sealed interface MemoryRetention {
    /** Return the content to actually store, given the candidate [content]. */
    fun apply(content: String): String

    /** Keep the last [maxLines] lines (FIFO — oldest dropped). The historical `maxLines` behavior. */
    class Sliding(val maxLines: Int) : MemoryRetention {
        init { require(maxLines > 0) { "Sliding maxLines must be positive, was $maxLines." } }
        override fun apply(content: String): String {
            val lines = content.lines()
            return if (lines.size > maxLines) lines.takeLast(maxLines).joinToString("\n") else content
        }
    }

    /** Keep the most recent lines until the estimated token count is within [maxTokens]. */
    class TokenBudget(
        val maxTokens: Int,
        val estimateTokens: (String) -> Int = ::estimateTokens,
    ) : MemoryRetention {
        init { require(maxTokens > 0) { "TokenBudget maxTokens must be positive, was $maxTokens." } }
        override fun apply(content: String): String {
            if (estimateTokens(content) <= maxTokens) return content
            val lines = content.lines().toMutableList()
            // Drop oldest lines until within budget, but always keep at least the most recent line.
            while (lines.size > 1 && estimateTokens(lines.joinToString("\n")) > maxTokens) {
                lines.removeAt(0)
            }
            return lines.joinToString("\n")
        }
    }

    /**
     * Keep the last [keepRecentLines] lines verbatim; collapse everything older into a single line
     * produced by [summarize]. The summarizer is the caller's (an LLM call, an extractive digest, …).
     */
    class Summarized(
        val keepRecentLines: Int,
        val summarize: (older: List<String>) -> String,
    ) : MemoryRetention {
        init { require(keepRecentLines >= 0) { "Summarized keepRecentLines must be >= 0, was $keepRecentLines." } }
        override fun apply(content: String): String {
            val lines = content.lines()
            if (lines.size <= keepRecentLines) return content
            val older = lines.dropLast(keepRecentLines)
            val recent = lines.takeLast(keepRecentLines)
            return (listOf(summarize(older)) + recent).joinToString("\n")
        }
    }

    /** Keep everything — only bounded by storage. */
    object Unbounded : MemoryRetention {
        override fun apply(content: String): String = content
    }
}

/** Rough token estimate (~4 chars/token), ceil. Good enough for budgeting; not a real tokenizer. */
fun estimateTokens(text: String): Int = (text.length + TOKEN_CHARS - 1) / TOKEN_CHARS

private const val TOKEN_CHARS = 4
