package agents_engine.model

import agents_engine.generation.LenientJsonParser
import org.junit.jupiter.api.assertThrows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Tests for #1644 — ClaudeClient (Anthropic Messages API adapter).
// Uses a sendChat() seam (same pattern as OllamaClient #706) so tests stub
// HTTP without standing up a server, and assert on the request body shape.
class ClaudeClientTest {

    private class StubClient(
        model: String,
        tools: List<ToolDef> = emptyList(),
        private val responses: ArrayDeque<String>,
        val sentBodies: MutableList<String> = mutableListOf(),
        val sentHeaders: MutableList<Map<String, String>> = mutableListOf(),
    ) : ClaudeClient(
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
    ) = StubClient("claude-opus-4-7", tools, ArrayDeque(responses.toList()))

    @Test
    fun `text response is parsed into LlmResponse Text with token usage`() {
        val client = stub(
            """{"id":"msg_01","type":"message","role":"assistant",
                "content":[{"type":"text","text":"hello world"}],
                "stop_reason":"end_turn",
                "usage":{"input_tokens":12,"output_tokens":3,"cache_read_input_tokens":4}}""".trimIndent(),
        )

        val resp = client.chat(listOf(LlmMessage("user", "hi")))

        assertTrue(resp is LlmResponse.Text, "expected Text, got ${resp::class.simpleName}")
        assertEquals("hello world", resp.content)
        assertEquals(
            TokenUsage(
                promptTokens = 12,
                completionTokens = 3,
                cachedInputTokens = 4,
                provider = "claude",
                model = "claude-opus-4-7",
            ),
            resp.tokenUsage,
        )
    }

    @Test
    fun `multiple text blocks are concatenated in order`() {
        val client = stub(
            """{"content":[
                {"type":"text","text":"part-A "},
                {"type":"text","text":"part-B"}
              ],"usage":{"input_tokens":1,"output_tokens":1}}""".trimIndent(),
        )

        val resp = client.chat(listOf(LlmMessage("user", "hi"))) as LlmResponse.Text
        assertEquals("part-A part-B", resp.content)
    }

    @Test
    fun `tool_use blocks become LlmResponse ToolCalls`() {
        val client = stub(
            """{"content":[
                {"type":"text","text":"thinking..."},
                {"type":"tool_use","id":"toolu_42","name":"fibonacci","input":{"n":10}}
              ],
              "stop_reason":"tool_use",
              "usage":{"input_tokens":20,"output_tokens":5}}""".trimIndent(),
            tools = listOf(ToolDef("fibonacci", "compute fib(n)") { it }),
        )

        val resp = client.chat(listOf(LlmMessage("user", "fib(10)?")))

        assertTrue(resp is LlmResponse.ToolCalls, "expected ToolCalls, got ${resp::class.simpleName}")
        val call = resp.calls.single()
        assertEquals("fibonacci", call.name)
        assertEquals(10, (call.arguments["n"] as Number).toInt())
        assertEquals(
            TokenUsage(
                promptTokens = 20,
                completionTokens = 5,
                cachedInputTokens = null,
                provider = "claude",
                model = "claude-opus-4-7",
            ),
            resp.tokenUsage,
        )
    }

    @Test
    fun `top-level error envelope raises LlmProviderException`() {
        val client = stub(
            """{"type":"error","error":{"type":"invalid_request_error","message":"missing model"}}""",
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
        val client = stub("""{"content":[{"type":"text","text":"ok"}]}""")
        val resp = client.chat(listOf(LlmMessage("user", "hi"))) as LlmResponse.Text
        assertNull(resp.tokenUsage, "no usage in response → null tokenUsage")
    }

    @Test
    fun `request headers include x-api-key and anthropic-version`() {
        val client = stub("""{"content":[{"type":"text","text":"ok"}]}""")
        client.chat(listOf(LlmMessage("user", "hi")))

        val h = client.sentHeaders.single()
        assertEquals("test-key", h["x-api-key"])
        assertNotNull(h["anthropic-version"], "anthropic-version header is required by the API")
        assertEquals("application/json", h["content-type"])
    }

    @Test
    fun `system message is hoisted to top-level system field, not in messages array`() {
        val client = stub("""{"content":[{"type":"text","text":"ok"}]}""")
        client.chat(listOf(
            LlmMessage("system", "You are helpful."),
            LlmMessage("user", "hi"),
        ))

        val body = client.sentBodies.single()
        val root = LenientJsonParser.parse(body) as Map<*, *>

        assertEquals("You are helpful.", root["system"], "system must be a top-level field")
        val messages = root["messages"] as List<*>
        assertEquals(1, messages.size, "system must NOT appear in messages array")
        val msg0 = messages[0] as Map<*, *>
        assertEquals("user", msg0["role"])
    }

    @Test
    fun `tools field uses input_schema (Anthropic spelling), not parameters`() {
        val client = stub(
            """{"content":[{"type":"text","text":"ok"}]}""",
            tools = listOf(ToolDef("greet", "Greet someone") { it }),
        )
        client.chat(listOf(LlmMessage("user", "hi")))

        val body = client.sentBodies.single()
        val root = LenientJsonParser.parse(body) as Map<*, *>
        val tools = root["tools"] as List<*>
        val tool = tools.single() as Map<*, *>

        assertEquals("greet", tool["name"])
        assertEquals("Greet someone", tool["description"])
        assertNotNull(tool["input_schema"], "Anthropic uses 'input_schema' not 'parameters'")
        assertNull(tool["parameters"], "Anthropic does NOT accept 'parameters'")
    }

    @Test
    fun `assistant tool calls and following tool results render as paired tool_use and tool_result blocks`() {
        val client = stub(
            """{"content":[{"type":"text","text":"done"}]}""",
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

        // Assistant message: structured content list with tool_use block.
        val assistant = messages[1] as Map<*, *>
        assertEquals("assistant", assistant["role"])
        val content = assistant["content"] as List<*>
        val toolUse = content.first { (it as Map<*, *>)["type"] == "tool_use" } as Map<*, *>
        val toolUseId = toolUse["id"] as String
        assertTrue(toolUseId.isNotBlank(), "synthetic tool_use id must be non-blank")
        assertEquals("fibonacci", toolUse["name"])
        val input = toolUse["input"] as Map<*, *>
        assertEquals(10, (input["n"] as Number).toInt())

        // Tool result: rendered as a user message with tool_result content
        // referencing the SAME synthetic id.
        val toolMsg = messages[2] as Map<*, *>
        assertEquals("user", toolMsg["role"])
        val toolContent = (toolMsg["content"] as List<*>).single() as Map<*, *>
        assertEquals("tool_result", toolContent["type"])
        assertEquals(toolUseId, toolContent["tool_use_id"], "tool_result must point at the matching tool_use id")
        assertEquals("55", toolContent["content"])
    }

    @Test
    fun `request body carries model, max_tokens, temperature`() {
        val client = stub("""{"content":[{"type":"text","text":"ok"}]}""")
        client.chat(listOf(LlmMessage("user", "hi")))

        val body = client.sentBodies.single()
        val root = LenientJsonParser.parse(body) as Map<*, *>

        assertEquals("claude-opus-4-7", root["model"])
        assertNotNull(root["max_tokens"], "Anthropic requires max_tokens")
        assertEquals(0.0, (root["temperature"] as Number).toDouble())
    }
}

class ClaudeModelDslTest {
    @Test
    fun `claude(name) selects ANTHROPIC provider and carries apiKey on the config`() {
        val cfg = ModelBuilder().apply {
            claude("claude-opus-4-7")
            apiKey = "sk-test"
            temperature = 0.1
            maxTokens = 8192
        }.build()

        assertEquals(ModelProvider.ANTHROPIC, cfg.provider)
        assertEquals("claude-opus-4-7", cfg.name)
        assertEquals("sk-test", cfg.apiKey)
        assertEquals(0.1, cfg.temperature)
        assertEquals(8192, cfg.maxTokens)
    }

    @Test
    fun `claude DSL without apiKey throws a clear error at build`() {
        val ex = assertThrows<IllegalStateException> {
            ModelBuilder().apply { claude("claude-opus-4-7") }.build()
        }
        assertTrue(
            ex.message!!.contains("apiKey"),
            "error must point at the missing apiKey; got: ${ex.message}",
        )
    }

    @Test
    fun `claude DSL accepts a pre-built client (escape hatch — no apiKey required)`() {
        val cfg = ModelBuilder().apply {
            claude("claude-opus-4-7")
            client = ClaudeClient(apiKey = "sk-test", model = "claude-opus-4-7")
        }.build()
        assertNotNull(cfg.client, "user-supplied client should pass through build")
    }
}
