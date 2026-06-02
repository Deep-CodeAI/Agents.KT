package agents_engine.model

import agents_engine.core.agent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #3376 batch 2 — pins the provider/client-construction contracts extracted out of `AgenticLoop`'s
 * private helpers into [ModelClientFactory]. `semconvProviderName` / `constrainedOutputSchemaFor`
 * were private (untestable); `defaultClientForTesting` is the #2385 seam, now namespaced.
 */
class ModelClientFactoryTest {

    @Test
    fun `semconvProviderName maps each provider to its wire name`() {
        assertEquals("anthropic", ModelClientFactory.semconvProviderName(ModelProvider.ANTHROPIC))
        assertEquals("ollama", ModelClientFactory.semconvProviderName(ModelProvider.OLLAMA))
        assertEquals("openrouter", ModelClientFactory.semconvProviderName(ModelProvider.OPENROUTER))
    }

    @Test
    fun `defaultClientForTesting builds the provider's client (Ollama needs no api key)`() {
        val client = ModelClientFactory.defaultClientForTesting(
            ModelConfig(name = "llama3", provider = ModelProvider.OLLAMA),
            tools = emptyList(),
        )
        assertTrue(client is OllamaClient, "OLLAMA config must build an OllamaClient: $client")
    }

    @Test
    fun `constrainedOutputSchemaFor is null when the client cannot constrain decoding`() {
        val skill = agent<String, String>("a") {
            skills { skill<String, String>("s", "d") { implementedBy { it } } }
        }.skills.values.first()
        val client = ModelClient { _ -> LlmResponse.Text("x") } // supportsConstrainedDecoding() = false
        assertNull(ModelClientFactory.constrainedOutputSchemaFor(String::class, skill, client))
    }
}
