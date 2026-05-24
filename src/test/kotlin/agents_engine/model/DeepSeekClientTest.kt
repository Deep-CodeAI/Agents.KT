package agents_engine.model

import agents_engine.generation.LenientJsonParser
import org.junit.jupiter.api.assertThrows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeepSeekClientTest {

    private class StubClient(
        model: String,
        tools: List<ToolDef> = emptyList(),
        private val responses: ArrayDeque<String>,
        val sentBodies: MutableList<String> = mutableListOf(),
        val sentHeaders: MutableList<Map<String, String>> = mutableListOf(),
    ) : DeepSeekClient(
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
    ) = StubClient("deepseek-v4-flash", tools, ArrayDeque(responses.toList()))

    @Test
    fun `text response is parsed with DeepSeek token usage identity`() {
        val client = stub(
            """{"choices":[{"message":{"role":"assistant","content":"pong"},"finish_reason":"stop"}],
              "usage":{"prompt_tokens":9,"completion_tokens":2,"total_tokens":11,
                "prompt_tokens_details":{"cached_tokens":1}}}""".trimIndent(),
        )

        val resp = client.chat(listOf(LlmMessage("user", "ping")))

        assertTrue(resp is LlmResponse.Text, "expected Text, got ${resp::class.simpleName}")
        assertEquals("pong", resp.content)
        assertEquals(
            TokenUsage(
                promptTokens = 9,
                completionTokens = 2,
                cachedInputTokens = 1,
                provider = "deepseek",
                model = "deepseek-v4-flash",
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
        assertNotNull(function["parameters"], "DeepSeek OpenAI-format tools use 'parameters'")

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
        assertNull(root["response_format"], "DeepSeek does not support OpenAI response_format.json_schema")
        assertFalse(client.supportsConstrainedDecoding())
    }

    @Test
    fun `request disables DeepSeek thinking mode for tool-loop compatibility`() {
        val client = stub("""{"choices":[{"message":{"content":"ok"}}]}""")

        client.chat(listOf(LlmMessage("user", "hi")))

        val root = LenientJsonParser.parse(client.sentBodies.single()) as Map<*, *>
        val thinking = root["thinking"] as? Map<*, *>
        assertNotNull(thinking, "DeepSeek request must include thinking mode control")
        assertEquals("disabled", thinking["type"])
    }

    @Test
    fun `top-level error envelope names DeepSeek`() {
        val client = stub(
            """{"error":{"type":"invalid_request_error","message":"bad model","code":"model_not_found"}}""",
        )

        val ex = assertThrows<LlmProviderException> {
            client.chat(listOf(LlmMessage("user", "hi")))
        }

        assertTrue(ex.message!!.contains("DeepSeek"), "expected provider label in error: ${ex.message}")
        assertTrue(ex.message!!.contains("bad model"), "expected provider reason in error: ${ex.message}")
    }

    @Test
    fun `headers include Authorization Bearer and content-type`() {
        val client = stub("""{"choices":[{"message":{"content":"ok"}}]}""")
        client.chat(listOf(LlmMessage("user", "hi")))

        val h = client.sentHeaders.single()
        assertEquals("Bearer test-key", h["Authorization"])
        assertEquals("application/json", h["content-type"])
    }
}

class DeepSeekModelDslTest {
    @Test
    fun `deepseek(name) selects DEEPSEEK provider and carries apiKey on the config`() {
        val cfg = ModelBuilder().apply {
            deepseek("deepseek-v4-flash")
            apiKey = "sk-deepseek-test"
            temperature = 0.1
            maxTokens = 2048
            deepSeekBaseUrl = "https://deepseek-gateway.example"
        }.build()

        assertEquals(ModelProvider.DEEPSEEK, cfg.provider)
        assertEquals("deepseek-v4-flash", cfg.name)
        assertEquals("sk-deepseek-test", cfg.apiKey)
        assertEquals(0.1, cfg.temperature)
        assertEquals(2048, cfg.maxTokens)
        assertEquals("https://deepseek-gateway.example", cfg.deepSeekBaseUrl)
    }

    @Test
    fun `deepseek DSL without apiKey throws a clear error at build`() {
        val ex = assertThrows<IllegalStateException> {
            ModelBuilder().apply { deepseek("deepseek-v4-flash") }.build()
        }
        assertTrue(
            ex.message!!.contains("apiKey"),
            "error must point at the missing apiKey; got: ${ex.message}",
        )
    }

    @Test
    fun `deepseek DSL accepts a pre-built client (escape hatch - no apiKey required)`() {
        val cfg = ModelBuilder().apply {
            deepseek("deepseek-v4-flash")
            client = DeepSeekClient(apiKey = "sk-test", model = "deepseek-v4-flash")
        }.build()
        assertNotNull(cfg.client, "user-supplied client should pass through build")
    }
}
