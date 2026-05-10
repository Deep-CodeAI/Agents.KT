package agents_engine.model

enum class ModelProvider { OLLAMA, ANTHROPIC }

data class ModelConfig(
    val name: String,
    val provider: ModelProvider,
    val temperature: Double = 0.7,
    val host: String = "localhost",
    val port: Int = 11434,
    val client: ModelClient? = null,
    /** Anthropic API key. Required when [provider] is [ModelProvider.ANTHROPIC]. */
    val apiKey: String? = null,
    /** Override the Anthropic base URL (tests, regional endpoints, proxies). */
    val anthropicBaseUrl: String = "https://api.anthropic.com",
    /** Anthropic requires max_tokens on every request. */
    val maxTokens: Int = 4096,
) {
    val baseUrl: String get() = "http://$host:$port"
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

    internal fun build(): ModelConfig {
        if (provider == ModelProvider.ANTHROPIC && client == null && apiKey == null) {
            error("model { claude(\"$name\") } requires apiKey to be set")
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
            maxTokens = maxTokens,
        )
    }
}
