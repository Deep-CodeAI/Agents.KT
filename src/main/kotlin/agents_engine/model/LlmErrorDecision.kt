package agents_engine.model

/**
 * `agents_engine/model/LlmErrorDecision.kt` (#3508) — the return type of `Agent.onLLMError`: what to
 * do when a model call fails (a [LlmProviderException] — incl. a down server, a provider 5xx, or a
 * malformed response).
 *
 * The default when no `onLLMError` is registered is equivalent to [Rethrow] — a configured model that
 * errors fails fast and loud. A handler can instead recover with [RespondWith], or ride out a
 * transient failure with [Retry] (#4495).
 */
sealed interface LlmErrorDecision {
    /** Re-throw the original [LlmProviderException] — fail fast and loud. The default. */
    object Rethrow : LlmErrorDecision

    /**
     * Recover: use [output] as the invocation's result instead of failing. The value is routed
     * through the agent's `castOut`, so it must be assignable to the agent's `OUT` (a `String` for a
     * `String`-output agent, a `@Generable` instance for a typed one) — a wrong type fails fast with a
     * `ClassCastException`, surfacing the mistake rather than masking it.
     */
    data class RespondWith(val output: Any?) : LlmErrorDecision

    /**
     * #4495 — retry the failed model call with exponential backoff: attempt *n*'s retry waits
     * `initialBackoffMillis * 2^(n-1)` (500ms, 1s, 2s, … by default). [maxAttempts] counts the
     * original call, so the default of 3 means up to two retries. The handler is consulted again on
     * every failure — returning [Retry] each time continues the schedule; switching to [RespondWith]
     * or [Rethrow] mid-schedule takes effect immediately. When attempts are exhausted the ORIGINAL
     * (last) error is rethrown, identity preserved, exactly as [Rethrow] would.
     *
     * Attempt counting is per model turn — a multi-turn agentic run gets a fresh budget each turn,
     * so one flaky turn can't starve the rest of the conversation.
     */
    data class Retry(
        val maxAttempts: Int = 3,
        val initialBackoffMillis: Long = 500,
    ) : LlmErrorDecision {
        init {
            require(maxAttempts > 0) { "maxAttempts must be positive, was $maxAttempts." }
            require(initialBackoffMillis >= 0) {
                "initialBackoffMillis must be non-negative, was $initialBackoffMillis."
            }
        }

        /** Backoff before retry number [retryNumber] (1-based): `initial * 2^(retryNumber-1)`, overflow-safe. */
        fun backoffBeforeRetry(retryNumber: Int): Long {
            val shift = (retryNumber - 1).coerceIn(0, MAX_BACKOFF_SHIFT)
            return initialBackoffMillis shl shift
        }

        private companion object {
            /** Caps the exponent so the shift can never overflow Long for sane initial values. */
            const val MAX_BACKOFF_SHIFT = 16
        }
    }
}
