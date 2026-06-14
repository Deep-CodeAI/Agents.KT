package agents_engine.model

import kotlinx.coroutines.flow.catch
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

    // #4511 — remember the endpoint so an auth failure can point at the region mismatch.
    private val configuredBaseUrl: String = baseUrl

    override fun chat(messages: List<LlmMessage>, jsonSchema: JsonSchema?): LlmResponse =
        try {
            super.chat(messages, jsonSchema = null)
        } catch (e: LlmProviderException) {
            throw regionAwareError(e)
        }

    override suspend fun chatStream(
        messages: List<LlmMessage>,
        jsonSchema: JsonSchema?,
    ): kotlinx.coroutines.flow.Flow<LlmChunk> =
        super.chatStream(messages, jsonSchema = null).catch { e ->
            throw if (e is LlmProviderException) regionAwareError(e) else e
        }

    /**
     * #4511 — Moonshot runs two SEPARATE platforms (`api.moonshot.cn` / China and
     * `api.moonshot.ai` / International); a key from one returns `Invalid Authentication`
     * against the other. On an auth error, append actionable region guidance so a dev
     * isn't left staring at a bare "Invalid Authentication". Non-auth errors pass through.
     */
    private fun regionAwareError(e: LlmProviderException): LlmProviderException {
        val lower = e.message.orEmpty().lowercase()
        if (AUTH_MARKERS.none { it in lower }) return e
        val otherRegion = if (configuredBaseUrl.trimEnd('/').endsWith(".cn")) {
            "If your key is from platform.moonshot.ai (International), use " +
                "KimiClient(baseUrl = KimiClient.INTERNATIONAL_BASE_URL) ($INTERNATIONAL_BASE_URL)."
        } else {
            "If your key is from platform.moonshot.cn (China), use " +
                "KimiClient(baseUrl = KimiClient.CHINA_BASE_URL) ($CHINA_BASE_URL)."
        }
        return LlmProviderException(
            "${e.message} — Moonshot has two separate platforms, $CHINA_BASE_URL (China) and " +
                "$INTERNATIONAL_BASE_URL (International); a key from one is rejected by the other. This client " +
                "is using '$configuredBaseUrl'. $otherRegion",
            e,
        )
    }

    companion object {
        /** Moonshot **China** platform — keys from platform.moonshot.cn. The historical default. */
        const val CHINA_BASE_URL: String = "https://api.moonshot.cn"

        /** Moonshot **International** platform — keys from platform.moonshot.ai. */
        const val INTERNATIONAL_BASE_URL: String = "https://api.moonshot.ai"

        const val DEFAULT_BASE_URL: String = CHINA_BASE_URL
        const val DEFAULT_MAX_TOKENS: Int = OpenAiClient.DEFAULT_MAX_TOKENS

        private val AUTH_MARKERS = listOf("invalid_authentication", "invalid authentication", "unauthorized", "401")
    }
}
