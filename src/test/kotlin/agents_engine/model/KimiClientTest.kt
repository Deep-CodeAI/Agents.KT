package agents_engine.model

import agents_engine.generation.LenientJsonParser
import org.junit.jupiter.api.assertThrows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #2697 — KimiClient unit tests. Mirrors `DeepSeekClientTest` because Kimi
 * uses the same OpenAI-compatible chat-completions wire format.
 *
 * Differences from DeepSeek that the tests pin:
 * - `providerName = "kimi"`, `providerLabel = "Kimi"` (token usage identity,
 *   provider mention in error envelopes).
 * - Kimi does NOT carry DeepSeek's `thinking` field — no provider-specific
 *   JSON payload override beyond what OpenAI sends.
 * - Default base URL is `https://api.moonshot.cn`.
 */
class KimiClientTest {

    private class StubClient(
        model: String,
        tools: List<ToolDef> = emptyList(),
        private val responses: ArrayDeque<String>,
        val sentBodies: MutableList<String> = mutableListOf(),
        val sentHeaders: MutableList<Map<String, String>> = mutableListOf(),
    ) : KimiClient(
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
    ) = StubClient("moonshot-v1-8k", tools, ArrayDeque(responses.toList()))

    @Test
    fun `text response is parsed with Kimi token usage identity`() {
        val client = stub(
            """{"choices":[{"message":{"role":"assistant","content":"pong"},"finish_reason":"stop"}],
              "usage":{"prompt_tokens":9,"completion_tokens":2,"total_tokens":11,
                "prompt_tokens_details":{"cached_tokens":1}}}""".trimIndent(),
        )

        val resp = client.chat(listOf(LlmMessage("user", "ping")))

        assertTrue(resp is LlmResponse.Text)
        assertEquals("pong", resp.content)
        assertEquals(
            TokenUsage(
                promptTokens = 9,
                completionTokens = 2,
                cachedInputTokens = 1,
                provider = "kimi",
                model = "moonshot-v1-8k",
            ),
            resp.tokenUsage,
        )
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

        val body = client.sentBodies.single()
        val root = LenientJsonParser.parse(body) as Map<*, *>
        val tools = root["tools"] as List<*>
        val function = (tools.single() as Map<*, *>)["function"] as Map<*, *>
        assertNotNull(function["parameters"], "Kimi OpenAI-format tools use 'parameters'")

        val messages = root["messages"] as List<*>
        val assistant = messages.first() as Map<*, *>
        val call = (assistant["tool_calls"] as List<*>).single() as Map<*, *>
        val args = ((call["function"] as Map<*, *>)["arguments"])
        assertTrue(args is String, "function.arguments must be stringified JSON")
        assertEquals("abc", (LenientJsonParser.parse(args) as Map<*, *>)["id"])

        val toolMessage = messages[1] as Map<*, *>
        assertEquals(call["id"], toolMessage["tool_call_id"])
    }

    @Test
    fun `schema-aware chat does not send OpenAI json_schema response_format`() {
        val client = stub("""{"choices":[{"message":{"content":"{}"}}]}""")

        client.chat(
            messages = listOf(LlmMessage("user", "answer as json")),
            jsonSchema = JsonSchema("Answer", """{"type":"object","properties":{}}"""),
        )

        val root = LenientJsonParser.parse(client.sentBodies.single()) as Map<*, *>
        assertNull(root["response_format"], "Kimi does not currently support OpenAI response_format.json_schema")
        assertFalse(client.supportsConstrainedDecoding())
    }

    @Test
    fun `Kimi request does NOT carry the DeepSeek thinking field`() {
        // DeepSeek-specific override; Kimi must not inherit it (would be a
        // confusing mis-pin if it did).
        val client = stub("""{"choices":[{"message":{"content":"ok"}}]}""")
        client.chat(listOf(LlmMessage("user", "hi")))
        val root = LenientJsonParser.parse(client.sentBodies.single()) as Map<*, *>
        assertNull(root["thinking"], "Kimi must not send DeepSeek's `thinking` payload")
    }

    @Test
    fun `top-level error envelope names Kimi`() {
        val client = stub(
            """{"error":{"type":"invalid_request_error","message":"bad model","code":"model_not_found"}}""",
        )

        val ex = assertThrows<LlmProviderException> {
            client.chat(listOf(LlmMessage("user", "hi")))
        }

        assertTrue(ex.message!!.contains("Kimi"), "expected provider label in error: ${ex.message}")
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
    fun `default base URL points at Moonshot AI`() {
        assertEquals("https://api.moonshot.cn", KimiClient.DEFAULT_BASE_URL)
    }
}

class KimiModelDslTest {
    @Test
    fun `kimi(name) selects KIMI provider and carries apiKey on the config`() {
        val cfg = ModelBuilder().apply {
            kimi("moonshot-v1-32k")
            apiKey = "sk-kimi-test"
            temperature = 0.1
            maxTokens = 2048
            kimiBaseUrl = "https://moonshot-gateway.example"
        }.build()

        assertEquals(ModelProvider.KIMI, cfg.provider)
        assertEquals("moonshot-v1-32k", cfg.name)
        assertEquals("sk-kimi-test", cfg.apiKey)
        assertEquals(0.1, cfg.temperature)
        assertEquals(2048, cfg.maxTokens)
        assertEquals("https://moonshot-gateway.example", cfg.kimiBaseUrl)
    }

    @Test
    fun `kimi DSL without apiKey throws a clear error at build`() {
        val ex = assertThrows<IllegalStateException> {
            ModelBuilder().apply { kimi("moonshot-v1-8k") }.build()
        }
        assertTrue(ex.message!!.contains("apiKey"))
        assertTrue(
            ex.message!!.contains("kimi-key") || ex.message!!.contains("kimi("),
            "error should point users at the kimi-key convention: ${ex.message}",
        )
    }

    @Test
    fun `kimi DSL accepts a pre-built client (escape hatch - no apiKey required)`() {
        val cfg = ModelBuilder().apply {
            kimi("moonshot-v1-8k")
            client = KimiClient(apiKey = "sk-test", model = "moonshot-v1-8k")
        }.build()
        assertNotNull(cfg.client)
    }

    @Test
    fun `ModelConfig toString masks apiKey and includes kimiBaseUrl`() {
        val cfg = ModelBuilder().apply {
            kimi("moonshot-v1-8k")
            apiKey = "sk-abcdef1234567890ZZZZZ"
        }.build()
        val s = cfg.toString()
        assertFalse(s.contains("sk-abcdef1234567890ZZZZZ"), "raw apiKey must not appear in toString: $s")
        assertTrue(s.contains("kimiBaseUrl="), "toString must include kimiBaseUrl field: $s")
    }
}
