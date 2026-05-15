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

    /**
     * #1722 — streaming entry point. Default impl wraps [chat] so existing
     * non-streaming providers keep working unchanged; providers with native
     * streaming override this to surface partial chunks.
     *
     * For a [LlmResponse.Text] result the default emits one [LlmChunk.TextDelta]
     * with the full content followed by [LlmChunk.End]. For [LlmResponse.ToolCalls]
     * each call expands into `Started → ArgumentsDelta → Finished`, then a single
     * `End`. The semantics match what a streaming provider would produce; only
     * the granularity differs (one big chunk vs. many).
     */
    suspend fun chatStream(messages: List<LlmMessage>): kotlinx.coroutines.flow.Flow<LlmChunk> =
        kotlinx.coroutines.flow.flow {
            val response = chat(messages)
            when (response) {
                is LlmResponse.Text -> {
                    emit(LlmChunk.TextDelta(response.content))
                    emit(LlmChunk.End(response.tokenUsage))
                }
                is LlmResponse.ToolCalls -> {
                    response.calls.forEach { call ->
                        val callId = java.util.UUID.randomUUID().toString()
                        emit(LlmChunk.ToolCallStarted(callId, call.name))
                        emit(LlmChunk.ToolCallArgumentsDelta(callId, call.rawArguments ?: ""))
                        emit(LlmChunk.ToolCallFinished(callId, call.arguments))
                    }
                    emit(LlmChunk.End(response.tokenUsage))
                }
            }
        }
}
