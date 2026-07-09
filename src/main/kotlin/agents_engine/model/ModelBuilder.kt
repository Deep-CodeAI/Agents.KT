package agents_engine.model

import java.net.http.HttpClient
import kotlin.time.Duration

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
    var perplexityBaseUrl: String = PerplexityClient.DEFAULT_BASE_URL
    var geminiBaseUrl: String = GeminiClient.DEFAULT_BASE_URL
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
    fun kimi(modelName: String, region: KimiRegion? = null) {
        name = modelName
        provider = ModelProvider.KIMI
        // #4883 — only override the base URL when a region is explicitly chosen, so existing
        // `kimi("...")` calls (and any manual `kimiBaseUrl = ...`) keep the China default untouched.
        if (region != null) kimiBaseUrl = region.baseUrl
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

    /**
     * Select Perplexity (Sonar) Chat Completions (#3675). [PerplexityClient]
     * is constructed lazily at AgenticLoop time so the agent's full tool
     * catalog is available. Model ids follow Perplexity's `sonar` family, e.g.
     * `"sonar"`, `"sonar-pro"`, `"sonar-reasoning-pro"`, `"sonar-deep-research"`.
     * For web-grounded search from an agent on a different model, prefer the
     * `perplexitySearch` tool (#3676) over selecting a sonar model here.
     */
    fun perplexity(modelName: String) {
        name = modelName
        provider = ModelProvider.PERPLEXITY
    }

    /**
     * Select Google Gemini (Generative Language API) (#1917). [GeminiClient] is constructed lazily
     * at AgenticLoop time so the agent's full tool catalog is available. Model ids follow Google's
     * naming, e.g. `"gemini-2.5-flash"`, `"gemini-2.5-pro"`, `"gemini-2.0-flash"`. Requires an API
     * key from Google AI Studio (load from `.secrets/gemini-key`).
     */
    fun gemini(modelName: String) {
        name = modelName
        provider = ModelProvider.GEMINI
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
                ModelProvider.PERPLEXITY -> error("model { perplexity(\"$name\") } requires apiKey to be set")
                ModelProvider.GEMINI -> error("model { gemini(\"$name\") } requires apiKey to be set")
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
            perplexityBaseUrl = perplexityBaseUrl,
            geminiBaseUrl = geminiBaseUrl,
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
