package agents_engine.model

import java.io.InputStream
import kotlin.time.Duration

/**
 * #2701 — OpenRouter ModelClient. OpenRouter exposes an OpenAI-compatible
 * `/api/v1/chat/completions` endpoint that fronts hundreds of upstream
 * models from many providers (`openai/gpt-4o-mini`, `anthropic/claude-3.5-sonnet`,
 * `meta-llama/llama-3.3-70b-instruct:free`, etc.). This adapter is a thin
 * [OpenAiClient] subclass — same wire format, different identity, base URL,
 * and two optional headers OpenRouter recognizes for attribution and routing
 * controls (`HTTP-Referer`, `X-Title`).
 *
 * Pattern mirrors [KimiClient] / [DeepSeekClient] (OpenAI-compatible
 * subclasses) — the only meaningful additions are the two attribution
 * headers, surfaced via [httpReferer] / [xTitle] and merged into every
 * outbound HTTP request.
 */
open class OpenRouterClient(
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
    /**
     * Optional `HTTP-Referer` header (OpenRouter convention) — the origin
     * URL of the calling app. Surfaces in OpenRouter's attribution UI and
     * is honored by some upstream provider policies.
     */
    private val httpReferer: String? = null,
    /**
     * Optional `X-Title` header (OpenRouter convention) — human-readable
     * name of the calling app for OpenRouter's attribution UI.
     */
    private val xTitle: String? = null,
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
    providerName = "openrouter",
    providerLabel = "OpenRouter",
    reasoning = reasoning,
    httpClient = httpClient,
) {
    /**
     * Upstream-model behavior for constrained decoding varies widely
     * across OpenRouter's catalog. Keep the framework-level schema gate
     * off so `@Generable` output parsing stays prompt/parser-driven
     * regardless of which upstream the request is routed to. Callers
     * who want strict JSON-schema mode can target a specific upstream
     * via the model id and a custom subclass if needed.
     */
    override fun supportsConstrainedDecoding(): Boolean = false

    override fun chat(messages: List<LlmMessage>, jsonSchema: JsonSchema?): LlmResponse =
        super.chat(messages, jsonSchema = null)

    override suspend fun chatStream(
        messages: List<LlmMessage>,
        jsonSchema: JsonSchema?,
    ): kotlinx.coroutines.flow.Flow<LlmChunk> =
        super.chatStream(messages, jsonSchema = null)

    override fun sendChat(body: String, headers: Map<String, String>): String =
        super.sendChat(body, withOpenRouterHeaders(headers))

    override fun sendChatStream(body: String, headers: Map<String, String>): InputStream =
        super.sendChatStream(body, withOpenRouterHeaders(headers))

    /**
     * Augments the OpenAI-format headers with OpenRouter's optional
     * `HTTP-Referer` / `X-Title` attribution headers when configured.
     * Exposed `internal` so tests stubbing `sendChat` can capture the
     * final outgoing header set without re-implementing the merge.
     */
    internal fun withOpenRouterHeaders(headers: Map<String, String>): Map<String, String> =
        if (httpReferer == null && xTitle == null) headers
        else buildMap {
            putAll(headers)
            httpReferer?.let { put("HTTP-Referer", it) }
            xTitle?.let { put("X-Title", it) }
        }

    companion object {
        const val DEFAULT_BASE_URL: String = "https://openrouter.ai/api"
        const val DEFAULT_MAX_TOKENS: Int = OpenAiClient.DEFAULT_MAX_TOKENS
    }
}
