package agents_engine.model

/**
 * `agents_engine/model/ModelClient.kt` — the LLM transport interface
 * ([ModelClient]) plus the shared types adapters speak in: [LlmMessage],
 * [ToolCall], [JsonSchema], [TokenUsage], [LlmResponse]. Defines the default
 * `chatStream(...)` wrapping `chat(...)` so non-streaming providers work
 * unchanged (#1722). Optional [JsonSchema] requests let adapters wire
 * provider-level constrained decoding for `@Generable` outputs (#1949). See
 * `src/main/resources/internals-agent/model/ModelClient.md` for the
 * adjunct surfaced to IDE-side LLM tools (#1837 / #1850).
 */

fun interface ModelClient {
    fun chat(messages: List<LlmMessage>): LlmResponse

    /**
     * Optional schema-aware chat path (#1949). The one-argument [chat] remains
     * the sole abstract method, preserving SAM lambdas for custom clients and
     * tests. Implementations that support provider-level constrained decoding
     * override this overload and [supportsConstrainedDecoding].
     */
    fun chat(messages: List<LlmMessage>, jsonSchema: JsonSchema?): LlmResponse =
        chat(messages)

    /** True when this provider can honor [JsonSchema] on at least non-streaming chat. */
    fun supportsConstrainedDecoding(): Boolean = false

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
        // #2792 — one-arg delegates to two-arg with no schema so the chunk-
        // emission body lives in one place. Pre-fix the two were byte-
        // identical except the inner chat() call.
        chatStream(messages, jsonSchema = null)

    /**
     * Optional schema-aware streaming path. Defaults to the existing streaming
     * behavior so providers can opt in independently from non-streaming chat.
     */
    suspend fun chatStream(
        messages: List<LlmMessage>,
        jsonSchema: JsonSchema?,
    ): kotlinx.coroutines.flow.Flow<LlmChunk> =
        kotlinx.coroutines.flow.flow {
            val response = chat(messages, jsonSchema)
            // #2406 — surface reasoning (when the non-streaming response carried it)
            // before the answer, so the chunk order matches a native stream.
            response.reasoning?.let { emit(LlmChunk.ReasoningDelta(it)) }
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
