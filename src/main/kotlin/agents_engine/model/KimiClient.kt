package agents_engine.model

import kotlin.time.Duration

/**
 * #2697 — Kimi (Moonshot AI) Chat Completions adapter. Kimi's public API at
 * `api.moonshot.cn/v1` is OpenAI-compatible at the chat-completions
 * wire format (messages, tools, tool_choice, streaming, function tool-call
 * deltas), so this adapter reuses the OpenAI mapping while keeping provider
 * identity, defaults, and constrained-decoding capability separate.
 *
 * Long-context model variants are addressable as `moonshot-v1-8k`,
 * `moonshot-v1-32k`, `moonshot-v1-128k` (the value goes into [model]).
 *
 * Pattern mirrors [DeepSeekClient] — a thin OpenAI-compatible subclass.
 */
open class KimiClient(
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
    /** #2385 — forwarded to the OpenAI-compatible superclass for shared-client injection. */
    httpClient: java.net.http.HttpClient? = null,
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
    providerName = "kimi",
    providerLabel = "Kimi",
    reasoning = reasoning,
    httpClient = httpClient,
) {
    /**
     * Kimi's documented `response_format` does not (yet) accept OpenAI's
     * `json_schema` payload. Keep the framework-level schema gate off so
     * `@Generable` output parsing remains prompt/parser-driven rather than
     * sending an unsupported provider field. Same posture as [DeepSeekClient].
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
        const val DEFAULT_BASE_URL: String = "https://api.moonshot.cn"
        const val DEFAULT_MAX_TOKENS: Int = OpenAiClient.DEFAULT_MAX_TOKENS
    }
}
