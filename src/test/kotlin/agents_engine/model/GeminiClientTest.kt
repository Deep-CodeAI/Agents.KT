package agents_engine.model

import org.junit.jupiter.api.assertThrows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// #1917 — Gemini (Generative Language API) adapter. Same sendChat() seam pattern as ClaudeClient
// (#1644) and OpenAiClient (#1656): a StubClient feeds canned response bodies and records request
// bodies so we can assert both the parse and the wire shape without a live server.

class GeminiClientTest {

    private class StubClient(
        model: String,
        tools: List<ToolDef> = emptyList(),
        toolChoice: ToolChoice = ToolChoice.Auto,
        private val responses: ArrayDeque<String>,
        val sentBodies: MutableList<String> = mutableListOf(),
        val sentEndpoints: MutableList<String> = mutableListOf(),
    ) : GeminiClient(
        apiKey = "test-key",
        model = model,
        temperature = 0.0,
        tools = tools,
        toolChoice = toolChoice,
    ) {
        override fun sendChat(body: String, endpoint: String): String {
            sentBodies.add(body)
            sentEndpoints.add(endpoint)
            check(responses.isNotEmpty()) { "StubClient ran out of canned responses" }
            return responses.removeFirst()
        }
    }

    private fun stub(
        vararg responses: String,
        tools: List<ToolDef> = emptyList(),
        toolChoice: ToolChoice = ToolChoice.Auto,
    ) = StubClient("gemini-2.5-flash", tools, toolChoice, ArrayDeque(responses.toList()))

    @Test
    fun `text response is parsed into LlmResponse Text with token usage`() {
        val client = stub(
            """{"candidates":[{"content":{"role":"model","parts":[{"text":"hello world"}]},
                "finishReason":"STOP"}],
               "usageMetadata":{"promptTokenCount":12,"candidatesTokenCount":3,
                 "totalTokenCount":15,"cachedContentTokenCount":2}}""".trimIndent(),
        )

        val resp = client.chat(listOf(LlmMessage("user", "hi")))

        assertTrue(resp is LlmResponse.Text, "expected Text, got ${resp::class.simpleName}")
        assertEquals("hello world", resp.content)
        assertEquals(
            TokenUsage(
                promptTokens = 12,
                completionTokens = 3,
                cachedInputTokens = 2,
                provider = "gemini",
                model = "gemini-2.5-flash",
            ),
            resp.tokenUsage,
        )
        assertEquals("generateContent", client.sentEndpoints.single())
    }

    @Test
    fun `functionCall part becomes LlmResponse ToolCalls`() {
        val client = stub(
            """{"candidates":[{"content":{"role":"model","parts":[
                 {"functionCall":{"name":"fibonacci","args":{"n":10}}}]},
                "finishReason":"STOP"}],
               "usageMetadata":{"promptTokenCount":20,"candidatesTokenCount":5,"totalTokenCount":25}}""".trimIndent(),
            tools = listOf(ToolDef("fibonacci", "compute fib(n)") { it }),
        )

        val resp = client.chat(listOf(LlmMessage("user", "fib(10)?")))

        assertTrue(resp is LlmResponse.ToolCalls, "expected ToolCalls, got ${resp::class.simpleName}")
        val call = resp.calls.single()
        assertEquals("fibonacci", call.name)
        assertEquals(10, (call.arguments["n"] as Number).toInt())
    }

    @Test
    fun `error envelope surfaces as LlmProviderException`() {
        val client = stub(
            """{"error":{"code":400,"message":"API key not valid","status":"INVALID_ARGUMENT"}}""",
        )
        val ex = assertThrows<LlmProviderException> { client.chat(listOf(LlmMessage("user", "hi"))) }
        assertTrue("API key not valid" in ex.message!!, "message should carry provider detail: ${ex.message}")
    }

    @Test
    fun `system message maps to systemInstruction and user to contents`() {
        val client = stub("""{"candidates":[{"content":{"parts":[{"text":"ok"}]}}]}""")
        client.chat(listOf(LlmMessage("system", "You are terse."), LlmMessage("user", "hi")))
        val body = client.sentBodies.single()
        assertTrue("\"systemInstruction\"" in body, "system → systemInstruction: $body")
        assertTrue("You are terse." in body, body)
        assertTrue("\"role\":\"user\"" in body, body)
    }

    @Test
    fun `tool defs render as functionDeclarations`() {
        val client = stub(
            """{"candidates":[{"content":{"parts":[{"text":"ok"}]}}]}""",
            tools = listOf(ToolDef("lookup", "look it up") { it }),
        )
        client.chat(listOf(LlmMessage("user", "go")))
        val body = client.sentBodies.single()
        assertTrue("\"functionDeclarations\"" in body, "tools → functionDeclarations: $body")
        assertTrue("\"name\":\"lookup\"" in body, body)
    }

    @Test
    fun `toolChoice Required maps to functionCallingConfig mode ANY`() {
        val client = stub(
            """{"candidates":[{"content":{"parts":[{"text":"ok"}]}}]}""",
            tools = listOf(ToolDef("lookup", "look it up") { it }),
            toolChoice = ToolChoice.Required,
        )
        client.chat(listOf(LlmMessage("user", "go")))
        val body = client.sentBodies.single()
        assertTrue("\"functionCallingConfig\"" in body && "\"mode\":\"ANY\"" in body, "tool_choice: $body")
    }

    @Test
    fun `constrained decoding requests responseJsonSchema and returns json text`() {
        val client = stub(
            """{"candidates":[{"content":{"parts":[{"text":"{\"answer\":42}"}]}}],
               "usageMetadata":{"promptTokenCount":5,"candidatesTokenCount":4}}""".trimIndent(),
        )
        val schema = JsonSchema("Answer", """{"type":"object","properties":{"answer":{"type":"integer"}}}""")
        val resp = client.chat(listOf(LlmMessage("user", "answer?")), jsonSchema = schema)

        val body = client.sentBodies.single()
        assertTrue("\"responseJsonSchema\"" in body, "structured → responseJsonSchema: $body")
        assertTrue("\"responseMimeType\":\"application/json\"" in body, body)
        assertTrue(resp is LlmResponse.Text && "42" in resp.content, "json text returned: $resp")
    }

    @Test
    fun `tool result pairs to functionResponse by name`() {
        val client = stub("""{"candidates":[{"content":{"parts":[{"text":"done"}]}}]}""")
        val convo = listOf(
            LlmMessage("user", "fib(10)?"),
            LlmMessage("assistant", "", toolCalls = listOf(ToolCall("fibonacci", mapOf("n" to 10)))),
            LlmMessage("tool", "55"),
        )
        client.chat(convo)
        val body = client.sentBodies.single()
        assertTrue("\"functionCall\"" in body && "\"functionResponse\"" in body, body)
        assertTrue("\"name\":\"fibonacci\"" in body, "functionResponse paired by name: $body")
        assertTrue("55" in body, body)
    }

    @Test
    fun `thought parts surface as reasoning separate from answer`() {
        val client = stub(
            """{"candidates":[{"content":{"parts":[
                 {"thought":true,"text":"thinking..."},
                 {"text":"final answer"}]}}],
               "usageMetadata":{"promptTokenCount":5,"candidatesTokenCount":4,"thoughtsTokenCount":3}}""".trimIndent(),
        )
        val resp = client.chat(listOf(LlmMessage("user", "q")))
        assertTrue(resp is LlmResponse.Text, resp::class.simpleName ?: "")
        assertEquals("final answer", resp.content)
        assertEquals("thinking...", resp.reasoning)
        assertEquals(3, resp.tokenUsage?.reasoningTokens)
    }
}
