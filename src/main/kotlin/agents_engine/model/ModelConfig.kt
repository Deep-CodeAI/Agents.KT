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

enum class ModelProvider { OLLAMA, ANTHROPIC, OPENAI, DEEPSEEK, KIMI, OPENROUTER }

/** Reasoning-effort hint for providers that take one (OpenAI `reasoning_effort`, Ollama). */
enum class ReasoningEffort { LOW, MEDIUM, HIGH }

/**
 * Opt-in reasoning/thinking configuration (#2406). Off unless set. When
 * enabled, providers that expose reasoning surface it via
 * `AgentEvent.Reasoning` / `LlmResponse.reasoning`:
 * - Claude: `thinking` with [budgetTokens] as the token budget.
 * - Ollama: `think: true`.
 * - DeepSeek: `reasoning_content` (stops force-disabling thinking).
 * - OpenAI: `reasoning_effort` from [effort]; surfaces reasoning *token counts*
 *   only — Chat Completions returns no reasoning text.
 */
data class ReasoningConfig(
    val enabled: Boolean = true,
    val budgetTokens: Int? = null,
    val effort: ReasoningEffort? = null,
)

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

class ModelBuilder {
    var name: String = ""
    var provider: ModelProvider = ModelProvider.OLLAMA
    var temperature: Double = 0.7
    var host: String = "localhost"
    var port: Int = 11434
    var client: ModelClient? = null
    var apiKey: String? = null
    var anthropicBaseUrl: String = "https://api.anthropic.com"
    var openAiBaseUrl: String = "https://api.openai.com"
    var deepSeekBaseUrl: String = DeepSeekClient.DEFAULT_BASE_URL
    var kimiBaseUrl: String = KimiClient.DEFAULT_BASE_URL
    var openRouterBaseUrl: String = OpenRouterClient.DEFAULT_BASE_URL
    var openRouterHttpReferer: String? = null
    var openRouterXTitle: String? = null
    var maxTokens: Int = ClaudeClient.DEFAULT_MAX_TOKENS
    /**
     * #2850 — override the adapter's [DEFAULT_REQUEST_TIMEOUT]. Null
     * (default) → use the adapter's 300s floor. Bump this when long
     * Sonnet turns / big Ollama generations / extended-thinking calls
     * regularly exceed the default; the JDK HttpClient aborts the call
     * at this cap and the framework surfaces it as
     * `HttpTimeoutException`.
     */
    var requestTimeout: Duration? = null
    /**
     * #2850 — override the adapter's [DEFAULT_CONNECT_TIMEOUT]. Null
     * (default) → 10s, which is right for every healthy network. Tune
     * for cross-region or slow-proxy deployments where TCP connect
     * itself is the bottleneck.
     */
    var connectTimeout: Duration? = null

    /** #2385 — opt into a shared `HttpClient` across agents (pool / proxy / rate-limit). */
    var httpClient: HttpClient? = null

    fun ollama(modelName: String) {
        name = modelName
        provider = ModelProvider.OLLAMA
    }

    /**
     * Select Anthropic Claude (#1644). [ClaudeClient] is constructed lazily at
     * AgenticLoop time so the agent's full tool catalog is available — same
     * pattern [OllamaClient] uses.
     */
    fun claude(modelName: String) {
        name = modelName
        provider = ModelProvider.ANTHROPIC
    }

    /**
     * Select OpenAI Chat Completions (#1656). [OpenAiClient] is constructed
     * lazily at AgenticLoop time so the agent's full tool catalog is available.
     */
    fun openai(modelName: String) {
        name = modelName
        provider = ModelProvider.OPENAI
    }

    /**
     * Select DeepSeek Chat Completions. [DeepSeekClient] is constructed lazily
     * at AgenticLoop time so the agent's full tool catalog is available.
     */
    fun deepseek(modelName: String) {
        name = modelName
        provider = ModelProvider.DEEPSEEK
    }

    /**
     * Select Kimi (Moonshot AI) Chat Completions (#2697). [KimiClient] is
     * constructed lazily at AgenticLoop time so the agent's full tool catalog
     * is available. Model names follow Moonshot's naming, e.g.
     * `"moonshot-v1-8k"`, `"moonshot-v1-32k"`, `"moonshot-v1-128k"`.
     */
    fun kimi(modelName: String) {
        name = modelName
        provider = ModelProvider.KIMI
    }

    /**
     * Select OpenRouter — the multi-provider OpenAI-compatible aggregator (#2701).
     * Model names follow OpenRouter's `provider/model` convention, e.g.
     * `"anthropic/claude-3.5-sonnet"`, `"openai/gpt-4o-mini"`,
     * `"meta-llama/llama-3.3-70b-instruct:free"` (free tier).
     * [OpenRouterClient] is constructed lazily at AgenticLoop time so the
     * agent's full tool catalog is available.
     */
    fun openrouter(modelName: String) {
        name = modelName
        provider = ModelProvider.OPENROUTER
    }

    /** Backing field for the [reasoning] DSL (#2406). Off by default. */
    private var reasoningConfig: ReasoningConfig? = null

    /**
     * Enable reasoning/thinking for this model (#2406). Off unless called.
     * `budgetTokens` feeds Claude's thinking budget; `effort` feeds OpenAI's
     * `reasoning_effort`. Providers that don't expose reasoning ignore it.
     */
    fun reasoning(
        enabled: Boolean = true,
        budgetTokens: Int? = null,
        effort: ReasoningEffort? = null,
    ) {
        reasoningConfig = ReasoningConfig(enabled, budgetTokens, effort)
    }

    internal fun build(): ModelConfig {
        if (client == null && apiKey == null) {
            when (provider) {
                ModelProvider.ANTHROPIC -> error("model { claude(\"$name\") } requires apiKey to be set")
                ModelProvider.OPENAI -> error("model { openai(\"$name\") } requires apiKey to be set")
                ModelProvider.DEEPSEEK -> error("model { deepseek(\"$name\") } requires apiKey to be set")
                ModelProvider.KIMI -> error("model { kimi(\"$name\") } requires apiKey to be set")
                ModelProvider.OPENROUTER -> error("model { openrouter(\"$name\") } requires apiKey to be set")
                ModelProvider.OLLAMA -> Unit
            }
        }
        return ModelConfig(
            name = name,
            provider = provider,
            temperature = temperature,
            host = host,
            port = port,
            client = client,
            apiKey = apiKey,
            anthropicBaseUrl = anthropicBaseUrl,
            openAiBaseUrl = openAiBaseUrl,
            deepSeekBaseUrl = deepSeekBaseUrl,
            kimiBaseUrl = kimiBaseUrl,
            openRouterBaseUrl = openRouterBaseUrl,
            openRouterHttpReferer = openRouterHttpReferer,
            openRouterXTitle = openRouterXTitle,
            maxTokens = maxTokens,
            reasoning = reasoningConfig,
            requestTimeout = requestTimeout,
            connectTimeout = connectTimeout,
            httpClient = httpClient,
        )
    }
}
