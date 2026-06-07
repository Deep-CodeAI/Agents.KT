package agents_engine.model

import kotlin.time.Duration

/**
 * #3675 — Perplexity (Sonar) Chat Completions adapter. Perplexity's public API
 * at `api.perplexity.ai/chat/completions` is OpenAI-compatible at the wire
 * format (messages, tools, tool_choice, streaming, `response_format`), so this
 * adapter reuses the OpenAI mapping while keeping provider identity, defaults,
 * and base URL separate. Pattern mirrors [KimiClient] / [DeepSeekClient] —
 * a thin OpenAI-compatible subclass.
 *
 * The differentiator (web-grounded answers + citations, recency/domain/mode
 * filters, `search_results`) is exposed via the `perplexitySearch` tool
 * (#3676 / #3677), NOT this connector — the connector just lets an agent
 * reason directly on a sonar model.
 *
 * Model ids (Perplexity cookbook): `sonar` (lightweight grounded search),
 * `sonar-pro` (advanced search + follow-ups), `sonar-reasoning-pro` (CoT
 * reasoning), `sonar-deep-research` (exhaustive multi-source reports). The
 * value goes into [model].
 *
 * Unlike DeepSeek/Kimi, Perplexity DOES accept OpenAI's `response_format`
 * with a `json_schema` payload, so constrained decoding is left ON (inherited
 * from [OpenAiClient]) — `@Generable` output can be requested as a strict
 * schema. See #3677 for the search-tool structured-output mapping.
 */
open class PerplexityClient(
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
    // #2479 part 2 — Perplexity inherits the OpenAI-compatible `tool_choice`
    // wire shape verbatim so the agentic loop can wire `agent.toolChoice`.
    toolChoice: ToolChoice = ToolChoice.Auto,
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
    providerName = "perplexity",
    providerLabel = "Perplexity",
    reasoning = reasoning,
    toolChoice = toolChoice,
    httpClient = httpClient,
) {
    companion object {
        const val DEFAULT_BASE_URL: String = "https://api.perplexity.ai"
        const val DEFAULT_MAX_TOKENS: Int = OpenAiClient.DEFAULT_MAX_TOKENS
    }
}
