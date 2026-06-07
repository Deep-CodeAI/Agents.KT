package agents_engine.model

import agents_engine.generation.LenientJsonParser
import org.junit.jupiter.api.assertThrows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * #3675 — PerplexityClient unit tests. Perplexity's `api.perplexity.ai`
 * Chat Completions surface is OpenAI-compatible, so this mirrors
 * `KimiClientTest` / `DeepSeekClientTest`.
 *
 * Pinned differences vs Kimi/DeepSeek:
 * - `providerName = "perplexity"`, `providerLabel = "Perplexity"` (token-usage
 *   identity, provider mention in error envelopes).
 * - Default base URL is `https://api.perplexity.ai`.
 * - Perplexity DOES accept OpenAI's `response_format.json_schema`, so
 *   constrained decoding is left ON (inherited) and the schema IS sent —
 *   the opposite of Kimi/DeepSeek, which suppress it.
 */
class PerplexityClientTest {

    private class StubClient(
        model: String,
        tools: List<ToolDef> = emptyList(),
        private val responses: ArrayDeque<String>,
        val sentBodies: MutableList<String> = mutableListOf(),
        val sentHeaders: MutableList<Map<String, String>> = mutableListOf(),
    ) : PerplexityClient(
        apiKey = "test-key",
        model = model,
        temperature = 0.0,
        tools = tools,
    ) {
        override fun sendChat(body: String, headers: Map<String, String>): String {
            sentBodies.add(body)
            sentHeaders.add(headers)
            check(responses.isNotEmpty()) { "StubClient ran out of canned responses" }
            return responses.removeFirst()
        }
    }

    private fun stub(
        vararg responses: String,
        tools: List<ToolDef> = emptyList(),
    ) = StubClient("sonar", tools, ArrayDeque(responses.toList()))

    @Test
    fun `text response is parsed with Perplexity token usage identity`() {
        val client = stub(
            """{"choices":[{"message":{"role":"assistant","content":"pong"},"finish_reason":"stop"}],
              "usage":{"prompt_tokens":9,"completion_tokens":2,"total_tokens":11}}""".trimIndent(),
        )

        val resp = client.chat(listOf(LlmMessage("user", "ping")))

        val text = assertIs<LlmResponse.Text>(resp)
        assertEquals("pong", text.content)
        assertEquals("perplexity", text.tokenUsage?.provider)
        assertEquals("sonar", text.tokenUsage?.model)
    }

    @Test
    fun `Perplexity supports constrained decoding and sends response_format json_schema`() {
        val client = stub("""{"choices":[{"message":{"content":"{}"}}]}""")

        assertTrue(
            client.supportsConstrainedDecoding(),
            "Perplexity accepts OpenAI response_format.json_schema — gate must stay ON",
        )

        client.chat(
            messages = listOf(LlmMessage("user", "answer as json")),
            jsonSchema = JsonSchema("Answer", """{"type":"object","properties":{}}"""),
        )

        val root = LenientJsonParser.parse(client.sentBodies.single()) as Map<*, *>
        val rf = assertNotNull(root["response_format"], "Perplexity must send response_format") as Map<*, *>
        assertEquals("json_schema", rf["type"])
    }

    @Test
    fun `tool calls use OpenAI-compatible parameters and stringified arguments`() {
        val client = stub(
            """{"choices":[{"message":{"content":"ok"}}]}""",
            tools = listOf(ToolDef("lookup", "Look up a value") { it }),
        )

        client.chat(listOf(
            LlmMessage("assistant", "", toolCalls = listOf(
                ToolCall(name = "lookup", arguments = mapOf("id" to "abc")),
            )),
            LlmMessage("tool", "found"),
        ))

        val root = LenientJsonParser.parse(client.sentBodies.single()) as Map<*, *>
        val tools = root["tools"] as List<*>
        val function = (tools.single() as Map<*, *>)["function"] as Map<*, *>
        assertNotNull(function["parameters"], "Perplexity OpenAI-format tools use 'parameters'")
    }

    @Test
    fun `top-level error envelope names Perplexity`() {
        val client = stub(
            """{"error":{"type":"invalid_request_error","message":"bad model","code":"model_not_found"}}""",
        )

        val ex = assertThrows<LlmProviderException> {
            client.chat(listOf(LlmMessage("user", "hi")))
        }

        assertTrue(ex.message!!.contains("Perplexity"), "expected provider label in error: ${ex.message}")
        assertTrue(ex.message!!.contains("bad model"))
    }

    @Test
    fun `headers include Authorization Bearer and content-type`() {
        val client = stub("""{"choices":[{"message":{"content":"ok"}}]}""")
        client.chat(listOf(LlmMessage("user", "hi")))

        val h = client.sentHeaders.single()
        assertEquals("Bearer test-key", h["Authorization"])
        assertEquals("application/json", h["content-type"])
    }

    @Test
    fun `default base URL points at Perplexity`() {
        assertEquals("https://api.perplexity.ai", PerplexityClient.DEFAULT_BASE_URL)
    }
}

class PerplexityModelDslTest {
    @Test
    fun `perplexity(name) selects PERPLEXITY provider and carries apiKey on the config`() {
        val cfg = ModelBuilder().apply {
            perplexity("sonar-pro")
            apiKey = "pplx-test"
            temperature = 0.1
            maxTokens = 2048
            perplexityBaseUrl = "https://pplx-gateway.example"
        }.build()

        assertEquals(ModelProvider.PERPLEXITY, cfg.provider)
        assertEquals("sonar-pro", cfg.name)
        assertEquals("pplx-test", cfg.apiKey)
        assertEquals(0.1, cfg.temperature)
        assertEquals(2048, cfg.maxTokens)
        assertEquals("https://pplx-gateway.example", cfg.perplexityBaseUrl)
    }

    @Test
    fun `perplexity DSL without apiKey throws a clear error pointing at the key convention`() {
        val ex = assertThrows<IllegalStateException> {
            ModelBuilder().apply { perplexity("sonar") }.build()
        }
        assertTrue(ex.message!!.contains("apiKey"))
        assertTrue(
            ex.message!!.contains("perplexity("),
            "error should point users at the perplexity DSL: ${ex.message}",
        )
    }

    @Test
    fun `perplexity DSL accepts a pre-built client (escape hatch - no apiKey required)`() {
        val cfg = ModelBuilder().apply {
            perplexity("sonar")
            client = PerplexityClient(apiKey = "pplx-test", model = "sonar")
        }.build()
        kotlin.test.assertNotNull(cfg.client)
    }

    @Test
    fun `ModelConfig toString masks apiKey and includes perplexityBaseUrl`() {
        val cfg = ModelBuilder().apply {
            perplexity("sonar")
            apiKey = "pplx-abcdef1234567890ZZZZZ"
        }.build()
        val s = cfg.toString()
        assertFalse(s.contains("pplx-abcdef1234567890ZZZZZ"), "raw apiKey must not appear in toString: $s")
        assertTrue(s.contains("perplexityBaseUrl="), "toString must include perplexityBaseUrl field: $s")
    }

    @Test
    fun `factory resolves PERPLEXITY config to a PerplexityClient with the configured base URL`() {
        val cfg = ModelBuilder().apply {
            perplexity("sonar")
            apiKey = "pplx-test"
            perplexityBaseUrl = "https://pplx-gateway.example"
        }.build()

        val client = ModelClientFactory.defaultClientForTesting(cfg, emptyList())
        assertIs<PerplexityClient>(client)
        assertEquals("perplexity", ModelClientFactory.semconvProviderName(ModelProvider.PERPLEXITY))
    }
}
