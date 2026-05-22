package agents_engine.model

/**
 * `agents_engine/model/ModelClient.kt` — the LLM transport interface
 * ([ModelClient]) plus the shared types adapters speak in: [LlmMessage],
 * [ToolCall], [TokenUsage], [LlmResponse]. Defines the default
 * `chatStream(...)` wrapping `chat(...)` so non-streaming providers work
 * unchanged (#1722). See
 * `src/main/resources/internals-agent/model/ModelClient.md` for the
 * adjunct surfaced to IDE-side LLM tools (#1837 / #1850).
 */

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
    /**
     * #1739 — provider-side call identifier. Set by streaming adapters
     * (Anthropic SSE `tool_use_id`, OpenAI `tool_call_id`, MCP) so the
     * agentic loop can correlate the chunks of one tool call back to a
     * single `AgentEvent.ToolCallStarted` / `ToolCallFinished` pair, even
     * under interleaved streaming. Nullable with default null — non-
     * streaming providers that don't surface an explicit id can leave
     * this empty.
     */
    val callId: String? = null,
)

/**
 * Token consumption for one LLM round-trip — null on the response when the
 * provider doesn't report it. Sum of prompt + completion is what counts toward
 * [BudgetConfig.maxTokens]. Cached input tokens are a provider-visible subset
 * of prompt tokens, not extra billable tokens to add to [total]. See #963/#2355.
 */
data class TokenUsage(
    val promptTokens: Int,
    val completionTokens: Int,
    val cachedInputTokens: Int? = null,
    val provider: String = "unknown",
    val model: String = "unknown",
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
                        // #1739: honor the provider's callId when supplied; synthesize
                        // only when the non-streaming `chat()` path returned a ToolCall
                        // without one. This keeps explicit ids stable end-to-end so
                        // AgentEvent.ToolCallStarted and ToolCallFinished can be
                        // matched by consumers.
                        val callId = call.callId ?: java.util.UUID.randomUUID().toString()
                        emit(LlmChunk.ToolCallStarted(callId, call.name))
                        emit(LlmChunk.ToolCallArgumentsDelta(callId, call.rawArguments ?: ""))
                        emit(LlmChunk.ToolCallFinished(callId, call.arguments))
                    }
                    emit(LlmChunk.End(response.tokenUsage))
                }
            }
        }
}
