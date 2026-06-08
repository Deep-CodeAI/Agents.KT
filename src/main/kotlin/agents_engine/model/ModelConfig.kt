package agents_engine.model

import java.net.http.HttpClient
import kotlin.time.Duration

/**
 * `agents_engine/model/ModelConfig.kt` — the `model { }` DSL slot:
 * provider enum, immutable config record, and the builder that maps
 * `ollama(...)` / `claude(...)` / `openai(...)` / `deepseek(...)` factory calls into a
 * [ModelConfig]. `toString` masks `apiKey` to avoid leaking it via
 * logger/stack-trace surfaces. See
 * `src/main/resources/internals-agent/model/ModelConfig.md` for the
 * adjunct surfaced to IDE-side LLM tools (#1837 / #1851).
 */

data class ModelConfig(
    val name: String,
    val provider: ModelProvider,
    val temperature: Double = 0.7,
    val host: String = "localhost",
    val port: Int = 11434,
    val client: ModelClient? = null,
    /** API key. Required for [ModelProvider.ANTHROPIC], [ModelProvider.OPENAI], and [ModelProvider.DEEPSEEK]. */
    val apiKey: String? = null,
    /** Override the Anthropic base URL (tests, regional endpoints, proxies). */
    val anthropicBaseUrl: String = "https://api.anthropic.com",
    /** Override the OpenAI base URL (Azure, regional endpoints, proxies). */
    val openAiBaseUrl: String = "https://api.openai.com",
    /** Override the DeepSeek base URL (regional endpoints, proxies, beta paths). */
    val deepSeekBaseUrl: String = DeepSeekClient.DEFAULT_BASE_URL,
    /** Override the Kimi (Moonshot) base URL — regional endpoints, proxies (#2697). */
    val kimiBaseUrl: String = KimiClient.DEFAULT_BASE_URL,
    /** Override the OpenRouter base URL (regional endpoints, proxies) — #2701. */
    val openRouterBaseUrl: String = OpenRouterClient.DEFAULT_BASE_URL,
    /** Override the Perplexity (Sonar) base URL — regional endpoints, proxies (#3675). */
    val perplexityBaseUrl: String = PerplexityClient.DEFAULT_BASE_URL,
    /** Optional `HTTP-Referer` header sent to OpenRouter — origin URL for attribution UI. */
    val openRouterHttpReferer: String? = null,
    /** Optional `X-Title` header sent to OpenRouter — calling app name for attribution UI. */
    val openRouterXTitle: String? = null,
    /** max_tokens carried on every Anthropic / OpenAI-compatible request. */
    val maxTokens: Int = 4096,
    /** Opt-in reasoning/thinking config (#2406); null = off (default, no behavior change). */
    val reasoning: ReasoningConfig? = null,
    /**
     * #2850 — wall-clock cap on a single LLM HTTP request. Null = use the
     * adapter's [DEFAULT_REQUEST_TIMEOUT] (300s on every built-in adapter
     * since the hotfix). Set this when long Sonnet turns, big Ollama
     * generations, or extended-thinking calls regularly exceed the
     * default — overshooting forces the JDK HttpClient to abort the call
     * mid-flight, which surfaces as `HttpTimeoutException: request timed
     * out` and tears down the streaming Flow.
     *
     * Wired via `model { requestTimeout = 10.minutes }` on the DSL.
     */
    val requestTimeout: Duration? = null,
    /**
     * #2850 — TCP connect timeout. Null = use the adapter's
     * [DEFAULT_CONNECT_TIMEOUT] (10s on every built-in adapter). Almost
     * never needs tuning — a healthy network never spends 10s on
     * connect. Exposed for symmetry with [requestTimeout] and for
     * exotic deployments (cross-region traffic, slow proxies) where
     * the connect leg itself is the bottleneck.
     */
    val connectTimeout: Duration? = null,
    /**
     * #2385 — optional shared `HttpClient` for this model's provider client.
     * Lets multiple agents share one connection pool / executor / proxy / telemetry
     * surface. Null (default) → each client builds its own, byte-for-byte unchanged.
     * Note: identity-compared in `equals`/`hashCode` (it is an infrastructure object,
     * not a value), so inject a stable instance if you rely on config-keyed caching.
     */
    val httpClient: HttpClient? = null,
) {
    val baseUrl: String get() = "http://$host:$port"

    // Security: the auto-generated data-class toString would include the raw
    // apiKey, which leaks through any logger / stack trace / future
    // serialization that calls toString on this config. Override to mask.
    // equals/hashCode still include apiKey — that's correct for cache keying
    // and doesn't leak through observation.
    override fun toString(): String =
        "ModelConfig(name=$name, provider=$provider, temperature=$temperature, " +
            "host=$host, port=$port, client=$client, apiKey=${maskApiKey(apiKey)}, " +
            "anthropicBaseUrl=$anthropicBaseUrl, openAiBaseUrl=$openAiBaseUrl, " +
            "deepSeekBaseUrl=$deepSeekBaseUrl, " +
            "kimiBaseUrl=$kimiBaseUrl, " +
            "openRouterBaseUrl=$openRouterBaseUrl, " +
            "perplexityBaseUrl=$perplexityBaseUrl, " +
            "openRouterHttpReferer=$openRouterHttpReferer, " +
            "openRouterXTitle=$openRouterXTitle, " +
            "maxTokens=$maxTokens, reasoning=$reasoning, " +
            "requestTimeout=$requestTimeout, connectTimeout=$connectTimeout)"

    private fun maskApiKey(key: String?): String = when {
        key == null -> "null"
        key.length <= 8 -> "***"
        // Show prefix + suffix length so misconfigurations (wrong key, swapped
        // env vars) are still diagnosable, without ever printing the body.
        else -> "${key.take(6)}…${key.length}chars"
    }
}
