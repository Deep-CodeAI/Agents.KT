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

data class LlmMessage(
    val role: String,
    val content: String,
    val toolCalls: List<ToolCall>? = null,
    /**
     * #2656 — optional vendor-neutral cache hint. When non-null, the
     * agentic loop is signalling that this message ends a cacheable
     * group; the adapter translates to its provider's mechanism
     * (Anthropic `cache_control` breakpoint, Gemini handle boundary,
     * etc.). Adapters that don't support caching ignore the hint —
     * caching is a latency / cost optimisation, not a correctness
     * condition. Defaults to null so pre-#2656 call sites are
     * unchanged on the wire.
     */
    val cacheHint: CacheHint? = null,
    /**
     * #2470 — optional vision input. When non-null and the role is
     * `"user"`, adapters translate each [ImagePart] into the provider's
     * native image payload alongside [content]:
     *
     *   - Ollama (e.g. qwen3-vl:8b) — `images: [<base64>, ...]` array
     *     on the user message; [content] stays the text prompt.
     *   - Anthropic Claude — `content: [{type:"text",...},
     *     {type:"image", source:{type:"base64", media_type:"image/png",
     *     data:"<base64>"}}, ...]`.
     *   - OpenAI — `content: [{type:"text",...},
     *     {type:"image_url", image_url:{url:"data:image/png;base64,
     *     <base64>"}}, ...]`.
     *
     * Null = no vision parts; wire shape is byte-identical to pre-#2470.
     * Vision works on the FIRST user turn (most common case for "describe
     * this image" prompts); subsequent user-turn images compose naturally
     * if the model supports multi-turn vision.
     *
     * Non-user roles ignore this field — system / assistant / tool
     * messages don't carry images in any provider's API.
     */
    val images: List<ImagePart>? = null,
)

/**
 * #2470 — base64-encoded image payload for vision input. The caller is
 * responsible for the encoding so the adapter can splat the bytes onto
 * the wire without re-encoding per provider. Wire MIME is closed via
 * the [ImagePart.WireMime] sealed type — `String` mime is intentionally
 * not accepted in the public ctor.
 *
 * Small, allocation-cheap. Equatability: `base64` is a `String`, so
 * structural equals/hashCode work — unlike `ByteArray`, which uses
 * identity equals (the trap we avoid by base64-encoding upfront).
 */
data class ImagePart(
    /** Base64-encoded image bytes, no `data:` URL prefix. Adapter formats per-provider. */
    val base64: String,
    /** Closed wire MIME — `image/png`, `image/jpeg`, `image/gif`, `image/webp`. */
    val wireMime: WireMime,
) {
    sealed interface WireMime {
        val value: String

        object Png : WireMime { override val value: String = "image/png" }
        object Jpeg : WireMime { override val value: String = "image/jpeg" }
        object Gif : WireMime { override val value: String = "image/gif" }
        object Webp : WireMime { override val value: String = "image/webp" }
    }
}

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
 * Provider-neutral structured-output schema request. [schema] is a JSON Schema
 * object encoded as JSON text; adapters embed it in their provider-specific
 * field (`response_format`, `format`, tool-shaped schema, etc.).
 */
data class JsonSchema(
    val name: String,
    val schema: String,
)

internal fun JsonSchema.wireName(): String =
    name
        .replace(Regex("[^A-Za-z0-9_-]"), "_")
        .trim('_')
        .ifBlank { "structured_output" }
        .take(64)

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
    /**
     * Reasoning tokens the provider billed inside [completionTokens] when a
     * reasoning model was used (#2411). A subset of completion tokens, not extra
     * — surfaced for cost/observability. Null when the provider doesn't report it.
     */
    val reasoningTokens: Int? = null,
    /**
     * Cache-write tokens billed at premium rate (#2663 — Anthropic prompt-caching
     * split). Anthropic charges 25% more for the tokens that *populated* the
     * cache and ~10% for [cachedInputTokens] that *hit* it; null on providers
     * that don't expose the write side (OpenAI / DeepSeek / Ollama report
     * cache reads only). A subset of [promptTokens] semantically — not extra
     * billable tokens to add to [total].
     */
    val cacheWriteTokens: Int? = null,
) {
    val total: Int get() = promptTokens + completionTokens

    /**
     * Cache hit ratio for this round-trip (#2663): cached input tokens as a
     * fraction of total prompt tokens. Null when the provider didn't report
     * cached-token usage, or when [promptTokens] is zero (no prompt to cache).
     * Range `[0.0, 1.0]`; `1.0` means the entire prompt hit the cache.
     */
    val cacheHitRate: Double?
        get() {
            val cached = cachedInputTokens ?: return null
            if (promptTokens <= 0) return null
            return cached.toDouble() / promptTokens.toDouble()
        }
}

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
