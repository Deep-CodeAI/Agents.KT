package agents_engine.model

/**
 * `agents_engine/model/ModelConfig.kt` — the `model { }` DSL slot:
 * provider enum, immutable config record, and the builder that maps
 * `ollama(...)` / `claude(...)` / `openai(...)` / `deepseek(...)` factory calls into a
 * [ModelConfig]. `toString` masks `apiKey` to avoid leaking it via
 * logger/stack-trace surfaces. See
 * `src/main/resources/internals-agent/model/ModelConfig.md` for the
 * adjunct surfaced to IDE-side LLM tools (#1837 / #1851).
 */

enum class ModelProvider { OLLAMA, ANTHROPIC, OPENAI, DEEPSEEK }

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
    /** max_tokens carried on every Anthropic / OpenAI-compatible request. */
    val maxTokens: Int = 4096,
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
            "maxTokens=$maxTokens)"

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
    var maxTokens: Int = ClaudeClient.DEFAULT_MAX_TOKENS

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

    internal fun build(): ModelConfig {
        if (client == null && apiKey == null) {
            when (provider) {
                ModelProvider.ANTHROPIC -> error("model { claude(\"$name\") } requires apiKey to be set")
                ModelProvider.OPENAI -> error("model { openai(\"$name\") } requires apiKey to be set")
                ModelProvider.DEEPSEEK -> error("model { deepseek(\"$name\") } requires apiKey to be set")
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
            maxTokens = maxTokens,
        )
    }
}
