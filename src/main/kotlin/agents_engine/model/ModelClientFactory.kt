package agents_engine.model

import agents_engine.core.Skill
import agents_engine.generation.hasGenerableAnnotation
import agents_engine.generation.jsonSchema
import kotlin.reflect.KClass

/**
 * Constructs the default `ModelClient` per provider and the related decoding/telemetry helpers
 * (#3376 — extracted from `AgenticLoop`'s private helpers so the contracts are unit-testable). A
 * user-supplied `config.client` still wins; this is only the fallback construction.
 */
internal object ModelClientFactory {

    /** Maps a provider to its OpenTelemetry-semconv wire name (used on `TokenUsage.provider`). */
    fun semconvProviderName(provider: ModelProvider): String =
        when (provider) {
            ModelProvider.ANTHROPIC -> "anthropic"
            ModelProvider.DEEPSEEK -> "deepseek"
            ModelProvider.OPENAI -> "openai"
            ModelProvider.OLLAMA -> "ollama"
            ModelProvider.KIMI -> "kimi"
            ModelProvider.OPENROUTER -> "openrouter"
        }

    /**
     * The provider-neutral [JsonSchema] to request for constrained decoding of a `@Generable` output,
     * or null when the client can't constrain, the skill has its own transformer, or the type isn't
     * `@Generable` (#1949).
     */
    fun constrainedOutputSchemaFor(
        outType: KClass<*>,
        skill: Skill<*, *>,
        client: ModelClient,
    ): JsonSchema? {
        if (!client.supportsConstrainedDecoding()) return null
        if (skill.outputTransformer != null) return null
        if (!outType.hasGenerableAnnotation()) return null
        return JsonSchema(
            name = outType.simpleName ?: "structured_output",
            schema = outType.jsonSchema(),
        )
    }

    // #1644 / #1656 — provider dispatch for the default client. Mirrors the prior eager
    // `OllamaClient(...)` construction; user-supplied `config.client` still wins. LongMethod-suppressed:
    // a flat `when` dispatch table that grows one construction block per provider (six now).
    @Suppress("LongMethod")
    fun defaultClientFor(
        config: ModelConfig,
        tools: List<ToolDef>,
        promptCacheKey: String? = null,
        // #2479 part 2 — agent.toolChoice flows through each adapter ctor. The adapters translate to
        // their provider's wire shape (or no-op + warn on Ollama, which has no native tool_choice).
        toolChoice: ToolChoice = ToolChoice.Auto,
    ): ModelClient =
        when (config.provider) {
            ModelProvider.OLLAMA -> OllamaClient(
                host = config.host,
                port = config.port,
                model = config.name,
                temperature = config.temperature,
                tools = tools,
                // #2850 — null falls back to the adapter's DEFAULT_REQUEST_TIMEOUT (300s on every
                // built-in adapter since the hotfix bump). The DSL field defaults to null.
                requestTimeout = config.requestTimeout ?: OllamaClient.DEFAULT_REQUEST_TIMEOUT,
                connectTimeout = config.connectTimeout ?: OllamaClient.DEFAULT_CONNECT_TIMEOUT,
                reasoning = config.reasoning,
                toolChoice = toolChoice,
                httpClient = config.httpClient,
            )
            ModelProvider.ANTHROPIC -> ClaudeClient(
                apiKey = config.apiKey
                    ?: error("Agent uses Claude but ModelConfig.apiKey is null — set apiKey in the model { } block"),
                model = config.name,
                temperature = config.temperature,
                maxTokens = config.maxTokens,
                tools = tools,
                baseUrl = config.anthropicBaseUrl,
                requestTimeout = config.requestTimeout ?: ClaudeClient.DEFAULT_REQUEST_TIMEOUT,
                connectTimeout = config.connectTimeout ?: ClaudeClient.DEFAULT_CONNECT_TIMEOUT,
                reasoning = config.reasoning,
                toolChoice = toolChoice,
                httpClient = config.httpClient,
            )
            ModelProvider.OPENAI -> OpenAiClient(
                apiKey = config.apiKey
                    ?: error("Agent uses OpenAI but ModelConfig.apiKey is null — set apiKey in the model { } block"),
                model = config.name,
                temperature = config.temperature,
                maxTokens = config.maxTokens,
                tools = tools,
                baseUrl = config.openAiBaseUrl,
                requestTimeout = config.requestTimeout ?: OpenAiClient.DEFAULT_REQUEST_TIMEOUT,
                connectTimeout = config.connectTimeout ?: OpenAiClient.DEFAULT_CONNECT_TIMEOUT,
                reasoning = config.reasoning,
                // #2659 — OpenAI automatic prefix caching: pass routing key when caching is enabled.
                promptCacheKey = promptCacheKey,
                toolChoice = toolChoice,
                httpClient = config.httpClient,
            )
            ModelProvider.DEEPSEEK -> DeepSeekClient(
                apiKey = config.apiKey
                    ?: error("Agent uses DeepSeek but ModelConfig.apiKey is null — set apiKey in the model { } block"),
                model = config.name,
                temperature = config.temperature,
                maxTokens = config.maxTokens,
                tools = tools,
                baseUrl = config.deepSeekBaseUrl,
                requestTimeout = config.requestTimeout ?: OpenAiClient.DEFAULT_REQUEST_TIMEOUT,
                connectTimeout = config.connectTimeout ?: OpenAiClient.DEFAULT_CONNECT_TIMEOUT,
                reasoning = config.reasoning,
                toolChoice = toolChoice,
                httpClient = config.httpClient,
            )
            // #2697 — Kimi (Moonshot AI) Chat Completions; thin OpenAI-compatible subclass, identical
            // wiring to DeepSeek but with the Moonshot base URL.
            ModelProvider.KIMI -> KimiClient(
                apiKey = config.apiKey
                    ?: error("Agent uses Kimi but ModelConfig.apiKey is null — load it from .secrets/kimi-key"),
                model = config.name,
                temperature = config.temperature,
                maxTokens = config.maxTokens,
                tools = tools,
                baseUrl = config.kimiBaseUrl,
                reasoning = config.reasoning,
                httpClient = config.httpClient,
            )
            // #2701 — OpenRouter is a thin OpenAI-compatible aggregator. Same wiring as DeepSeek/Kimi
            // but with the two optional attribution headers carried through ModelConfig.
            ModelProvider.OPENROUTER -> OpenRouterClient(
                apiKey = config.apiKey
                    ?: error("Agent uses OpenRouter but ModelConfig.apiKey is null"),
                model = config.name,
                temperature = config.temperature,
                maxTokens = config.maxTokens,
                tools = tools,
                baseUrl = config.openRouterBaseUrl,
                reasoning = config.reasoning,
                httpReferer = config.openRouterHttpReferer,
                xTitle = config.openRouterXTitle,
                httpClient = config.httpClient,
            )
        }

    // #2385 — internal seam exposing the otherwise-private defaultClientFor dispatch so tests can
    // assert ModelConfig.httpClient forwarding without reflection.
    fun defaultClientForTesting(config: ModelConfig, tools: List<ToolDef>): ModelClient =
        defaultClientFor(config, tools)
}
