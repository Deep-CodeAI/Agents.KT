package agents_engine.model

import agents_engine.generation.LenientJsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// Tests for #850 — every interpolated string in OllamaClient.buildRequestJson
// must go through toJsonString(). A value containing `"` or `\` must round-trip
// through LenientJsonParser cleanly.
class OllamaRequestJsonEscapingTest {

    private fun parsedRequest(
        model: String = "test-model",
        tools: List<ToolDef> = emptyList(),
        messages: List<LlmMessage> = listOf(LlmMessage("user", "hi")),
    ): Map<String, Any?> {
        val client = OllamaClient(host = "localhost", port = 11434, model = model, tools = tools)
        val body = client.buildRequestJson(messages)
        @Suppress("UNCHECKED_CAST")
        return LenientJsonParser.parse(body) as? Map<String, Any?>
            ?: error("OllamaClient produced unparseable JSON: $body")
    }

    @Test
    fun `tool name with embedded quote and backslash round-trips through escaping`() {
        val def = ToolDef(name = """foo"bar\baz""", description = "ok") { _ -> "x" }
        val body = parsedRequest(tools = listOf(def))
        @Suppress("UNCHECKED_CAST")
        val tools = body["tools"] as List<Map<String, Any?>>
        @Suppress("UNCHECKED_CAST")
        val fn = tools.single()["function"] as Map<String, Any?>
        assertEquals("""foo"bar\baz""", fn["name"])
    }

    @Test
    fun `model name containing special chars round-trips through escaping`() {
        val body = parsedRequest(model = """my"weird\model""")
        assertEquals("""my"weird\model""", body["model"])
    }

    @Test
    fun `assistant tool_call name with special chars does not corrupt next-turn JSON`() {
        // Simulates an LLM emitting a tool call whose name contains a quote — this is
        // the self-injection vector. The next-turn request must still parse.
        val toolCalls = listOf(ToolCall(name = """ev"il""", arguments = mapOf("x" to "y")))
        val body = parsedRequest(
            messages = listOf(
                LlmMessage("user", "hi"),
                LlmMessage("assistant", "", toolCalls),
                LlmMessage("tool", "result"),
            ),
        )
        @Suppress("UNCHECKED_CAST")
        val msgs = body["messages"] as List<Map<String, Any?>>
        val assistant = msgs[1]
        @Suppress("UNCHECKED_CAST")
        val calls = assistant["tool_calls"] as List<Map<String, Any?>>
        @Suppress("UNCHECKED_CAST")
        val fn = calls.single()["function"] as Map<String, Any?>
        assertEquals("""ev"il""", fn["name"])
    }

    @Test
    fun `message role is escaped (defensive against future role extensions)`() {
        // Role is currently framework-controlled but the escape path must still honor
        // weird inputs to prevent regressions if roles ever become user-extensible.
        val body = parsedRequest(messages = listOf(LlmMessage("""sys"tem""", "hi")))
        @Suppress("UNCHECKED_CAST")
        val msgs = body["messages"] as List<Map<String, Any?>>
        assertEquals("""sys"tem""", msgs.single()["role"])
    }

    @Test
    fun `newline and tab in tool description still escape correctly (regression)`() {
        val def = ToolDef(name = "ok", description = "line1\nline2\tindent") { _ -> "x" }
        val body = parsedRequest(tools = listOf(def))
        @Suppress("UNCHECKED_CAST")
        val tools = body["tools"] as List<Map<String, Any?>>
        @Suppress("UNCHECKED_CAST")
        val fn = tools.single()["function"] as Map<String, Any?>
        assertEquals("line1\nline2\tindent", fn["description"])
    }

    @Test
    fun `request JSON is parseable when nothing special needs escaping (sanity)`() {
        val body = parsedRequest(
            tools = listOf(ToolDef(name = "plain", description = "plain") { _ -> "x" }),
        )
        assertEquals("test-model", body["model"])
        assertNotNull(body["messages"])
        assertNotNull(body["tools"])
        assertTrue(body.containsKey("stream"))
    }
}
