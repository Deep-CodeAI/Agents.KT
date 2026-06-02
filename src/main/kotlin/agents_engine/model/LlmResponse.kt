package agents_engine.model

sealed interface LlmResponse {
    /** Token usage for this response, or null if the provider didn't report it. */
    val tokenUsage: TokenUsage?

    /**
     * Accumulated reasoning/thinking text when reasoning was enabled and the
     * provider exposed it (#2406); null otherwise. Distinct from the answer
     * ([Text.content]) — it's the model's internal reasoning, not its reply.
     */
    val reasoning: String?

    data class Text(
        val content: String,
        override val tokenUsage: TokenUsage? = null,
        override val reasoning: String? = null,
    ) : LlmResponse

    data class ToolCalls(
        val calls: List<ToolCall>,
        override val tokenUsage: TokenUsage? = null,
        override val reasoning: String? = null,
    ) : LlmResponse
}
