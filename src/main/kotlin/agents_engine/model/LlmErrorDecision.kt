package agents_engine.model

/**
 * `agents_engine/model/LlmErrorDecision.kt` (#3508) — the return type of `Agent.onLLMError`: what to
 * do when a model call fails (a [LlmProviderException] — incl. a down server, a provider 5xx, or a
 * malformed response).
 *
 * The default when no `onLLMError` is registered is equivalent to [Rethrow] — a configured model that
 * errors fails fast and loud. A handler can instead recover with [RespondWith].
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
}
