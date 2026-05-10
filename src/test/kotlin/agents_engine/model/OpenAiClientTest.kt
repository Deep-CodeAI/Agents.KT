package agents_engine.model

import agents_engine.generation.LenientJsonParser
import org.junit.jupiter.api.assertThrows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Tests for #1656 — OpenAiClient (Chat Completions API adapter).
// Same sendChat() seam pattern as OllamaClient (#706) and ClaudeClient (#1644).
class OpenAiClientTest {

    private class StubClient(
        model: String,
        tools: List<ToolDef> = emptyList(),
        private val responses: ArrayDeque<String>,
        val sentBodies: MutableList<String> = mutableListOf(),
        val sentHeaders: MutableList<Map<String, String>> = mutableListOf(),
    ) : OpenAiClient(
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
    ) = StubClient("gpt-4o", tools, ArrayDeque(responses.toList()))

    @Test
    fun `text response is parsed into LlmResponse Text with token usage`() {
        val client = stub(
            """{"id":"chatcmpl_01","object":"chat.completion",
                "choices":[{"index":0,
                  "message":{"role":"assistant","content":"hello world"},
                  "finish_reason":"stop"}],
                "usage":{"prompt_tokens":12,"completion_tokens":3,"total_tokens":15}}""".trimIndent(),
        )

        val resp = client.chat(listOf(LlmMessage("user", "hi")))

        assertTrue(resp is LlmResponse.Text, "expected Text, got ${resp::class.simpleName}")
        assertEquals("hello world", resp.content)
        assertEquals(TokenUsage(12, 3), resp.tokenUsage)
    }

    @Test
    fun `tool_calls in response become LlmResponse ToolCalls (arguments arrive as stringified JSON)`() {
        val client = stub(
            """{"choices":[{"index":0,"message":{"role":"assistant","content":null,
                "tool_calls":[
                  {"id":"call_abc","type":"function",
                   "function":{"name":"fibonacci","arguments":"{\"n\":10}"}}
                ]},"finish_reason":"tool_calls"}],
              "usage":{"prompt_tokens":20,"completion_tokens":5,"total_tokens":25}}""".trimIndent(),
            tools = listOf(ToolDef("fibonacci", "compute fib(n)") { it }),
        )

        val resp = client.chat(listOf(LlmMessage("user", "fib(10)?")))

        assertTrue(resp is LlmResponse.ToolCalls, "expected ToolCalls, got ${resp::class.simpleName}")
        val call = resp.calls.single()
        assertEquals("fibonacci", call.name)
        assertEquals(10, (call.arguments["n"] as Number).toInt())
        assertEquals(TokenUsage(20, 5), resp.tokenUsage)
    }

    @Test
    fun `top-level error envelope raises LlmProviderException`() {
        val client = stub(
            """{"error":{"type":"invalid_request_error","message":"missing model","code":"model_not_found"}}""",
        )
        val ex = assertThrows<LlmProviderException> {
            client.chat(listOf(LlmMessage("user", "hi")))
        }
        assertTrue(
            ex.message!!.contains("missing model") || ex.message!!.contains("invalid_request_error"),
            "error must surface provider reason; got: ${ex.message}",
        )
    }

    @Test
    fun `missing usage field yields null tokenUsage`() {
        val client = stub("""{"choices":[{"message":{"role":"assistant","content":"ok"}}]}""")
        val resp = client.chat(listOf(LlmMessage("user", "hi"))) as LlmResponse.Text
        assertNull(resp.tokenUsage, "no usage in response → null tokenUsage")
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
    fun `system message stays in the messages array (NOT hoisted like Anthropic)`() {
        val client = stub("""{"choices":[{"message":{"content":"ok"}}]}""")
        client.chat(listOf(
            LlmMessage("system", "You are helpful."),
            LlmMessage("user", "hi"),
        ))

        val body = client.sentBodies.single()
        val root = LenientJsonParser.parse(body) as Map<*, *>

        // OpenAI keeps `system` in messages, not as a top-level field.
        assertNull(root["system"], "OpenAI must NOT hoist system to a top-level field")
        val messages = root["messages"] as List<*>
        assertEquals(2, messages.size, "system must stay in messages array")
        val msg0 = messages[0] as Map<*, *>
        assertEquals("system", msg0["role"])
        assertEquals("You are helpful.", msg0["content"])
    }

    @Test
    fun `tools field uses parameters (OpenAI spelling), not input_schema`() {
        val client = stub(
            """{"choices":[{"message":{"content":"ok"}}]}""",
            tools = listOf(ToolDef("greet", "Greet someone") { it }),
        )
        client.chat(listOf(LlmMessage("user", "hi")))

        val body = client.sentBodies.single()
        val root = LenientJsonParser.parse(body) as Map<*, *>
        val tools = root["tools"] as List<*>
        val tool = tools.single() as Map<*, *>

        assertEquals("function", tool["type"])
        val function = tool["function"] as Map<*, *>
        assertEquals("greet", function["name"])
        assertEquals("Greet someone", function["description"])
        assertNotNull(function["parameters"], "OpenAI uses 'parameters', not 'input_schema'")
        assertNull(function["input_schema"], "OpenAI does NOT accept 'input_schema'")
    }

    @Test
    fun `assistant tool calls and following tool results render with paired tool_call_ids`() {
        val client = stub(
            """{"choices":[{"message":{"content":"done"}}]}""",
            tools = listOf(ToolDef("fibonacci", "fib") { it }),
        )

        client.chat(listOf(
            LlmMessage("user", "fib(10)?"),
            LlmMessage("assistant", "", toolCalls = listOf(
                ToolCall(name = "fibonacci", arguments = mapOf("n" to 10)),
            )),
            LlmMessage("tool", "55"),
        ))

        val body = client.sentBodies.single()
        val root = LenientJsonParser.parse(body) as Map<*, *>
        val messages = root["messages"] as List<*>
        assertEquals(3, messages.size)

        // Assistant message: tool_calls array with synthesized id, function.arguments as stringified JSON.
        val assistant = messages[1] as Map<*, *>
        assertEquals("assistant", assistant["role"])
        val toolCalls = assistant["tool_calls"] as List<*>
        val tc = toolCalls.single() as Map<*, *>
        val callId = tc["id"] as String
        assertTrue(callId.isNotBlank(), "synthetic call id must be non-blank")
        assertEquals("function", tc["type"])
        val function = tc["function"] as Map<*, *>
        assertEquals("fibonacci", function["name"])
        // arguments is a STRING (stringified JSON) — OpenAI's wire convention.
        val argsString = function["arguments"]
        assertTrue(argsString is String, "tool_calls.function.arguments must be a stringified JSON, was ${argsString?.let { it::class.simpleName }}")
        val parsedArgs = LenientJsonParser.parse(argsString) as Map<*, *>
        assertEquals(10, (parsedArgs["n"] as Number).toInt())

        // Tool result: role=tool, tool_call_id pointing at the matching id.
        val toolMsg = messages[2] as Map<*, *>
        assertEquals("tool", toolMsg["role"])
        assertEquals(callId, toolMsg["tool_call_id"], "tool message must reference the matching tool_call id")
        assertEquals("55", toolMsg["content"])
    }

    @Test
    fun `request body carries model, temperature, max_tokens`() {
        val client = stub("""{"choices":[{"message":{"content":"ok"}}]}""")
        client.chat(listOf(LlmMessage("user", "hi")))

        val body = client.sentBodies.single()
        val root = LenientJsonParser.parse(body) as Map<*, *>

        assertEquals("gpt-4o", root["model"])
        assertEquals(0.0, (root["temperature"] as Number).toDouble())
        assertNotNull(root["max_tokens"], "max_tokens carried so cost is bounded")
    }
}

class OpenAiModelDslTest {
    @Test
    fun `openai(name) selects OPENAI provider and carries apiKey on the config`() {
        val cfg = ModelBuilder().apply {
            openai("gpt-4o")
            apiKey = "sk-test"
            temperature = 0.1
            maxTokens = 8192
        }.build()

        assertEquals(ModelProvider.OPENAI, cfg.provider)
        assertEquals("gpt-4o", cfg.name)
        assertEquals("sk-test", cfg.apiKey)
        assertEquals(0.1, cfg.temperature)
        assertEquals(8192, cfg.maxTokens)
    }

    @Test
    fun `openai DSL without apiKey throws a clear error at build`() {
        val ex = assertThrows<IllegalStateException> {
            ModelBuilder().apply { openai("gpt-4o") }.build()
        }
        assertTrue(
            ex.message!!.contains("apiKey"),
            "error must point at the missing apiKey; got: ${ex.message}",
        )
    }

    @Test
    fun `openai DSL accepts a pre-built client (escape hatch — no apiKey required)`() {
        val cfg = ModelBuilder().apply {
            openai("gpt-4o")
            client = OpenAiClient(apiKey = "sk-test", model = "gpt-4o")
        }.build()
        assertNotNull(cfg.client, "user-supplied client should pass through build")
    }
}
