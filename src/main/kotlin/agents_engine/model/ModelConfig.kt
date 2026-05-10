package agents_engine.model

enum class ModelProvider { OLLAMA, ANTHROPIC, OPENAI }

data class ModelConfig(
    val name: String,
    val provider: ModelProvider,
    val temperature: Double = 0.7,
    val host: String = "localhost",
    val port: Int = 11434,
    val client: ModelClient? = null,
    /** API key. Required for [ModelProvider.ANTHROPIC] and [ModelProvider.OPENAI]. */
    val apiKey: String? = null,
    /** Override the Anthropic base URL (tests, regional endpoints, proxies). */
    val anthropicBaseUrl: String = "https://api.anthropic.com",
    /** Override the OpenAI base URL (Azure, regional endpoints, proxies). */
    val openAiBaseUrl: String = "https://api.openai.com",
    /** max_tokens carried on every Anthropic / OpenAI request. */
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
    var openAiBaseUrl: String = "https://api.openai.com"
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

    internal fun build(): ModelConfig {
        if (client == null && apiKey == null) {
            when (provider) {
                ModelProvider.ANTHROPIC -> error("model { claude(\"$name\") } requires apiKey to be set")
                ModelProvider.OPENAI -> error("model { openai(\"$name\") } requires apiKey to be set")
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
            maxTokens = maxTokens,
        )
    }
}
