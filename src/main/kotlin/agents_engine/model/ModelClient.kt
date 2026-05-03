package agents_engine.model

data class LlmMessage(
    val role: String,
    val content: String,
    val toolCalls: List<ToolCall>? = null,
)

data class ToolCall(
    val name: String,
    val arguments: Map<String, Any?> = emptyMap(),
    val rawArguments: String? = null,
    val invalidArgumentsError: String? = null,
)

/**
 * Token consumption for one LLM round-trip — null on the response when the
 * provider doesn't report it. Sum of prompt + completion is what counts toward
 * [BudgetConfig.maxTokens]. See #963.
 */
data class TokenUsage(
    val promptTokens: Int,
    val completionTokens: Int,
) {
    val total: Int get() = promptTokens + completionTokens
}

sealed interface LlmResponse {
    /** Token usage for this response, or null if the provider didn't report it. */
    val tokenUsage: TokenUsage?

    data class Text(
        val content: String,
        override val tokenUsage: TokenUsage? = null,
    ) : LlmResponse

    data class ToolCalls(
        val calls: List<ToolCall>,
        override val tokenUsage: TokenUsage? = null,
    ) : LlmResponse
}

fun interface ModelClient {
    fun chat(messages: List<LlmMessage>): LlmResponse
}
