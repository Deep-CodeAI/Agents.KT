package agents_engine.model

import kotlin.time.Duration

/**
 * DeepSeek Chat Completions adapter. DeepSeek's public API exposes an
 * OpenAI-format `/chat/completions` surface, so this adapter reuses the
 * OpenAI-compatible message/tool/SSE mapping while keeping provider identity,
 * defaults, and constrained-decoding capability separate.
 */
open class DeepSeekClient(
    apiKey: String,
    model: String,
    temperature: Double = 0.7,
    maxTokens: Int = DEFAULT_MAX_TOKENS,
    tools: List<ToolDef> = emptyList(),
    baseUrl: String = DEFAULT_BASE_URL,
    requestTimeout: Duration = OpenAiClient.DEFAULT_REQUEST_TIMEOUT,
    connectTimeout: Duration = OpenAiClient.DEFAULT_CONNECT_TIMEOUT,
    maxResponseBytes: Long = OpenAiClient.DEFAULT_MAX_RESPONSE_BYTES,
    reasoning: ReasoningConfig? = null,
) : OpenAiClient(
    apiKey = apiKey,
    model = model,
    temperature = temperature,
    maxTokens = maxTokens,
    tools = tools,
    baseUrl = baseUrl,
    requestTimeout = requestTimeout,
    connectTimeout = connectTimeout,
    maxResponseBytes = maxResponseBytes,
    providerName = "deepseek",
    providerLabel = "DeepSeek",
    reasoning = reasoning,
) {
    /**
     * #2409 — by default DeepSeek thinking is disabled (preserves prior
     * behavior). When reasoning is opted in, stop disabling so the reasoner
     * emits `reasoning_content` (parsed by the shared OpenAI-compatible path).
     */
    override fun additionalRequestJsonFields(
        stream: Boolean,
        jsonSchema: JsonSchema?,
    ): String =
        if (reasoning?.enabled == true) "" else ""","thinking":{"type":"disabled"}"""

    /**
     * DeepSeek supports JSON object mode, but its documented `response_format`
     * currently does not accept OpenAI's `json_schema` payload. Keep the
     * framework-level schema gate off so `@Generable` output parsing remains
     * prompt/parser-driven rather than sending an unsupported provider field.
     */
    override fun supportsConstrainedDecoding(): Boolean = false

    override fun chat(messages: List<LlmMessage>, jsonSchema: JsonSchema?): LlmResponse =
        super.chat(messages, jsonSchema = null)

    override suspend fun chatStream(
        messages: List<LlmMessage>,
        jsonSchema: JsonSchema?,
    ): kotlinx.coroutines.flow.Flow<LlmChunk> =
        super.chatStream(messages, jsonSchema = null)

    companion object {
        const val DEFAULT_BASE_URL: String = "https://api.deepseek.com"
        const val DEFAULT_MAX_TOKENS: Int = OpenAiClient.DEFAULT_MAX_TOKENS
    }
}
