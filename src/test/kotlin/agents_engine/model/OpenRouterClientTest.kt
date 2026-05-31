package agents_engine.model

import agents_engine.generation.LenientJsonParser
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #2701 — OpenRouterClient unit tests. Mirrors `KimiClientTest` /
 * `DeepSeekClientTest` because OpenRouter exposes an OpenAI-compatible
 * wire format. Additional pins specific to OpenRouter:
 *
 * - `providerName = "openrouter"`, `providerLabel = "OpenRouter"`.
 * - Default base URL is `https://openrouter.ai/api`.
 * - Optional `HTTP-Referer` and `X-Title` headers are injected per call
 *   when configured.
 * - No DeepSeek `thinking` payload leak.
 */
class OpenRouterClientTest {

    private class StubClient(
        model: String,
        tools: List<ToolDef> = emptyList(),
        httpReferer: String? = null,
        xTitle: String? = null,
        private val responses: ArrayDeque<String>,
        val sentBodies: MutableList<String> = mutableListOf(),
        val sentHeaders: MutableList<Map<String, String>> = mutableListOf(),
        val sentStreamHeaders: MutableList<Map<String, String>> = mutableListOf(),
    ) : OpenRouterClient(
        apiKey = "test-key",
        model = model,
        temperature = 0.0,
        tools = tools,
        httpReferer = httpReferer,
        xTitle = xTitle,
    ) {
        override fun sendChat(body: String, headers: Map<String, String>): String {
            sentBodies.add(body)
            // Capture the FULL outgoing header set — what the real OpenAI
            // parent would send — by reusing OpenRouter's merge helper.
            sentHeaders.add(withOpenRouterHeaders(headers))
            check(responses.isNotEmpty()) { "StubClient ran out of canned responses" }
            return responses.removeFirst()
        }

        override fun sendChatStream(body: String, headers: Map<String, String>): InputStream {
            sentStreamHeaders.add(withOpenRouterHeaders(headers))
            // Terminal SSE — OpenAI dialect ends with `data: [DONE]`.
            return ByteArrayInputStream("data: [DONE]\n\n".toByteArray())
        }
    }

    private fun stub(
        vararg responses: String,
        tools: List<ToolDef> = emptyList(),
        httpReferer: String? = null,
        xTitle: String? = null,
    ) = StubClient(
        model = "openai/gpt-4o-mini",
        tools = tools,
        httpReferer = httpReferer,
        xTitle = xTitle,
        responses = ArrayDeque(responses.toList()),
    )

    @Test
    fun `text response is parsed with OpenRouter token usage identity`() {
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
                provider = "openrouter",
                model = "openai/gpt-4o-mini",
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
        assertNotNull(function["parameters"], "OpenRouter OpenAI-format tools use 'parameters'")

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
        assertNull(root["response_format"], "OpenRouter routes to many upstreams; framework keeps json_schema off")
        assertFalse(client.supportsConstrainedDecoding())
    }

    @Test
    fun `OpenRouter request does NOT carry the DeepSeek thinking field`() {
        // DeepSeek-specific override; OpenRouter must not inherit it.
        val client = stub("""{"choices":[{"message":{"content":"ok"}}]}""")
        client.chat(listOf(LlmMessage("user", "hi")))
        val root = LenientJsonParser.parse(client.sentBodies.single()) as Map<*, *>
        assertNull(root["thinking"], "OpenRouter must not send DeepSeek's `thinking` payload")
    }

    @Test
    fun `top-level error envelope names OpenRouter`() {
        val client = stub(
            """{"error":{"type":"invalid_request_error","message":"bad model","code":"model_not_found"}}""",
        )

        val ex = assertThrows<LlmProviderException> {
            client.chat(listOf(LlmMessage("user", "hi")))
        }

        assertTrue(ex.message!!.contains("OpenRouter"), "expected provider label in error: ${ex.message}")
        assertTrue(ex.message!!.contains("bad model"))
    }

    @Test
    fun `headers include Authorization Bearer and content-type by default`() {
        val client = stub("""{"choices":[{"message":{"content":"ok"}}]}""")
        client.chat(listOf(LlmMessage("user", "hi")))

        val h = client.sentHeaders.single()
        assertEquals("Bearer test-key", h["Authorization"])
        assertEquals("application/json", h["content-type"])
    }

    @Test
    fun `HTTP-Referer and X-Title headers are added when configured`() {
        val client = stub(
            """{"choices":[{"message":{"content":"ok"}}]}""",
            httpReferer = "https://example.app",
            xTitle = "Example App",
        )
        client.chat(listOf(LlmMessage("user", "hi")))

        val h = client.sentHeaders.single()
        assertEquals("https://example.app", h["HTTP-Referer"])
        assertEquals("Example App", h["X-Title"])
        // Standard headers still present
        assertEquals("Bearer test-key", h["Authorization"])
    }

    @Test
    fun `HTTP-Referer and X-Title are absent when not configured`() {
        // Default constructor leaves both null — confirm no spurious headers leak.
        val client = stub("""{"choices":[{"message":{"content":"ok"}}]}""")
        client.chat(listOf(LlmMessage("user", "hi")))

        val h = client.sentHeaders.single()
        assertFalse(h.containsKey("HTTP-Referer"))
        assertFalse(h.containsKey("X-Title"))
    }

    @Test
    fun `streaming requests also carry the OpenRouter attribution headers`() = kotlinx.coroutines.runBlocking {
        val client = stub(
            httpReferer = "https://stream.example",
            xTitle = "Stream Test",
        )
        client.chatStream(listOf(LlmMessage("user", "stream please"))).collect { /* drain */ }

        val h = client.sentStreamHeaders.single()
        assertEquals("https://stream.example", h["HTTP-Referer"])
        assertEquals("Stream Test", h["X-Title"])
    }

    @Test
    fun `default base URL points at openrouter ai`() {
        assertEquals("https://openrouter.ai/api", OpenRouterClient.DEFAULT_BASE_URL)
    }
}

class OpenRouterModelDslTest {
    @Test
    fun `openrouter(name) selects OPENROUTER provider and carries apiKey on the config`() {
        val cfg = ModelBuilder().apply {
            openrouter("anthropic/claude-3.5-sonnet")
            apiKey = "sk-or-v1-test"
            temperature = 0.1
            maxTokens = 2048
            openRouterBaseUrl = "https://openrouter-gateway.example"
            openRouterHttpReferer = "https://agents-kt.dev"
            openRouterXTitle = "Agents.KT live test"
        }.build()

        assertEquals(ModelProvider.OPENROUTER, cfg.provider)
        assertEquals("anthropic/claude-3.5-sonnet", cfg.name)
        assertEquals("sk-or-v1-test", cfg.apiKey)
        assertEquals(0.1, cfg.temperature)
        assertEquals(2048, cfg.maxTokens)
        assertEquals("https://openrouter-gateway.example", cfg.openRouterBaseUrl)
        assertEquals("https://agents-kt.dev", cfg.openRouterHttpReferer)
        assertEquals("Agents.KT live test", cfg.openRouterXTitle)
    }

    @Test
    fun `openrouter DSL without apiKey throws a clear error at build`() {
        val ex = assertThrows<IllegalStateException> {
            ModelBuilder().apply { openrouter("openai/gpt-4o-mini") }.build()
        }
        assertTrue(ex.message!!.contains("apiKey"))
        assertTrue(
            ex.message!!.contains("open-router-key") || ex.message!!.contains("openrouter("),
            "error should point users at the open-router-key convention: ${ex.message}",
        )
    }

    @Test
    fun `openrouter DSL accepts a pre-built client (escape hatch - no apiKey required)`() {
        val cfg = ModelBuilder().apply {
            openrouter("openai/gpt-4o-mini")
            client = OpenRouterClient(apiKey = "sk-test", model = "openai/gpt-4o-mini")
        }.build()
        assertNotNull(cfg.client)
    }

    @Test
    fun `ModelConfig toString masks apiKey and includes OpenRouter fields`() {
        val cfg = ModelBuilder().apply {
            openrouter("openai/gpt-4o-mini")
            apiKey = "sk-or-v1-XXXXXXXXXXXXXXX"
            openRouterHttpReferer = "https://example"
            openRouterXTitle = "App"
        }.build()
        val s = cfg.toString()
        assertFalse(s.contains("sk-or-v1-XXXXXXXXXXXXXXX"), "raw apiKey must not leak: $s")
        assertTrue(s.contains("openRouterBaseUrl="))
        assertTrue(s.contains("openRouterHttpReferer=https://example"))
        assertTrue(s.contains("openRouterXTitle=App"))
    }
}
