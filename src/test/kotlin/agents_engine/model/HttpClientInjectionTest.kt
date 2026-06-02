package agents_engine.model

import java.net.http.HttpClient
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/**
 * #2385 — provider clients accept an optional `httpClient: HttpClient?` so a
 * long-lived process can share one connection pool / executor / proxy across
 * every agent in the JVM. When the parameter is null, each client builds its own
 * (byte-for-byte unchanged behavior).
 *
 * The internal `httpClient` accessor is the stable test seam — same shape as the
 * existing `internal fun sendChat` / `buildRequestJson` seams on these clients.
 */
class HttpClientInjectionTest {

    private val shared: HttpClient = HttpClient.newBuilder().build()

    @Test
    fun `OllamaClient honors injected HttpClient`() {
        val client = OllamaClient(model = "test", httpClient = shared)
        assertSame(shared, client.httpClient, "Ollama must use the injected HttpClient verbatim")
    }

    @Test
    fun `OllamaClient builds its own HttpClient when none injected`() {
        val client = OllamaClient(model = "test")
        assertNotNull(client.httpClient)
        assertNotSame(shared, client.httpClient, "must not silently use any shared default")
    }

    @Test
    fun `OpenAiClient honors injected HttpClient`() {
        assertSame(shared, OpenAiClient(apiKey = "k", model = "test", httpClient = shared).httpClient)
    }

    @Test
    fun `ClaudeClient honors injected HttpClient`() {
        assertSame(shared, ClaudeClient(apiKey = "k", model = "test", httpClient = shared).httpClient)
    }

    @Test
    fun `DeepSeekClient inherits injected HttpClient through OpenAi superclass`() {
        assertSame(shared, DeepSeekClient(apiKey = "k", model = "test", httpClient = shared).httpClient)
    }

    @Test
    fun `KimiClient inherits injected HttpClient through OpenAi superclass`() {
        assertSame(shared, KimiClient(apiKey = "k", model = "test", httpClient = shared).httpClient)
    }

    @Test
    fun `OpenRouterClient inherits injected HttpClient through OpenAi superclass`() {
        assertSame(shared, OpenRouterClient(apiKey = "k", model = "test", httpClient = shared).httpClient)
    }

    @Test
    fun `same HttpClient instance can serve multiple clients across providers`() {
        val ollama = OllamaClient(model = "m1", httpClient = shared)
        val openai = OpenAiClient(apiKey = "k", model = "m2", httpClient = shared)
        val claude = ClaudeClient(apiKey = "k", model = "m3", httpClient = shared)
        val deepseek = DeepSeekClient(apiKey = "k", model = "m4", httpClient = shared)
        assertSame(shared, ollama.httpClient)
        assertSame(shared, openai.httpClient)
        assertSame(shared, claude.httpClient)
        assertSame(shared, deepseek.httpClient)
    }

    @Test
    fun `ModelConfig forwards httpClient through defaultClientFor for every provider`() {
        // The ModelConfig DSL slot must reach the per-provider client; otherwise the
        // injection API exists on the bare constructors but isn't reachable from the
        // `model { }` block — defeating the use case.
        val providers = listOf(
            ModelProvider.OLLAMA,
            ModelProvider.ANTHROPIC,
            ModelProvider.OPENAI,
            ModelProvider.DEEPSEEK,
            ModelProvider.KIMI,
            ModelProvider.OPENROUTER,
        )
        providers.forEach { provider ->
            val config = ModelConfig(
                name = "test-$provider",
                provider = provider,
                apiKey = if (provider == ModelProvider.OLLAMA) null else "k",
                httpClient = shared,
            )
            val client = ModelClientFactory.defaultClientForTesting(config, tools = emptyList())
            val resolved = when (client) {
                is OllamaClient -> client.httpClient
                is OpenAiClient -> client.httpClient // also catches DeepSeekClient (subclass)
                is ClaudeClient -> client.httpClient
                else -> error("unexpected client type: ${client::class.simpleName}")
            }
            assertSame(shared, resolved, "provider=$provider must forward injected HttpClient")
        }
    }
}
