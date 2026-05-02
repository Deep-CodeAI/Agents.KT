package agents_engine.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Tests for #706 — when Ollama rejects a request because the chosen model
 * doesn't support native `tools` (e.g. gemma3:4b), `OllamaClient.chat`
 * transparently retries:
 *   - tools field stripped from the request body, and
 *   - tool catalog injected into a system message in the inline JSON tool
 *     call format that `InlineToolCallParser` already consumes.
 *
 * Other provider errors (auth, model-not-found, transport) still propagate
 * as `LlmProviderException` — only the tool-capability case auto-recovers.
 */
class OllamaInlineToolFallbackTest {

    private class StubClient(
        model: String,
        tools: List<ToolDef>,
        private val responses: ArrayDeque<String>,
        val sentBodies: MutableList<String> = mutableListOf(),
    ) : OllamaClient(model = model, tools = tools, temperature = 0.0) {
        override fun sendChat(body: String): String {
            sentBodies.add(body)
            check(responses.isNotEmpty()) { "StubClient ran out of canned responses" }
            return responses.removeFirst()
        }
    }

    private fun stub(vararg responses: String, tools: List<ToolDef> = listOf(ToolDef("greet", "Greet someone") { it })) =
        StubClient("gemma3:4b", tools, ArrayDeque(responses.toList()))

    @Test
    fun `chat retries without tools and parses inline JSON when model rejects native tool capability`() {
        val client = stub(
            """{"error":"registry.ollama.ai/library/gemma3:4b does not support tools"}""",
            """{"message":{"role":"assistant","content":"{\"tool\":\"greet\",\"arguments\":{\"name\":\"Alice\"}}"}}""",
        )

        val resp = client.chat(listOf(LlmMessage("user", "Greet Alice.")))

        assertTrue(resp is LlmResponse.ToolCalls, "fallback must surface tool calls, got ${resp::class.simpleName}")
        val call = resp.calls.single()
        assertEquals("greet", call.name)
        assertEquals("Alice", call.arguments["name"])
    }

    @Test
    fun `retry request omits tools field and injects inline tool prompt as system message`() {
        val client = stub(
            """{"error":"... does not support tools"}""",
            """{"message":{"content":"hello"}}""",
        )
        client.chat(listOf(LlmMessage("user", "hi")))

        assertEquals(2, client.sentBodies.size, "must send exactly two requests: original + fallback")
        val first = client.sentBodies[0]
        val second = client.sentBodies[1]

        assertTrue(first.contains("\"tools\":["), "first request must carry native tools")
        assertFalse(second.contains("\"tools\":["), "fallback request must NOT advertise native tools")
        assertTrue(second.contains("\"role\":\"system\""), "fallback must inject a system message")
        assertTrue(second.contains("greet"), "fallback system message must list available tool names")
        assertTrue(second.contains("\\\"tool\\\""), "fallback system message must show the inline {\"tool\":...} format")
    }

    @Test
    fun `retry preserves existing user-supplied system message and appends inline tool format`() {
        val client = stub(
            """{"error":"does not support tools"}""",
            """{"message":{"content":"ok"}}""",
        )
        client.chat(listOf(
            LlmMessage("system", "You are a helpful assistant."),
            LlmMessage("user", "hi"),
        ))

        val second = client.sentBodies[1]
        assertTrue(
            second.contains("You are a helpful assistant"),
            "user's original system content must survive the retry, got: $second",
        )
        assertTrue(second.contains("greet"), "tool catalog must be appended to the system message")
        // Still exactly one system message — not two.
        val systemCount = "\"role\":\"system\"".toRegex().findAll(second).count()
        assertEquals(1, systemCount, "must merge into a single system message, not duplicate")
    }

    @Test
    fun `non-capability provider error propagates without retry`() {
        val client = stub("""{"error":"model 'nope' not found, try pulling it first"}""")
        try {
            client.chat(listOf(LlmMessage("user", "hi")))
            fail("expected LlmProviderException for non-capability error")
        } catch (e: LlmProviderException) {
            assertTrue(e.message!!.contains("not found"))
        }
        assertEquals(1, client.sentBodies.size, "must not retry on non-capability errors")
    }

    @Test
    fun `no retry when no tools are configured even on capability error`() {
        // Defensive: if the user didn't ask for tools, a capability error is
        // weird but there's nothing useful to retry — propagate it.
        val client = stub(
            """{"error":"does not support tools"}""",
            tools = emptyList(),
        )
        try {
            client.chat(listOf(LlmMessage("user", "hi")))
            fail("expected LlmProviderException")
        } catch (_: LlmProviderException) { }
        assertEquals(1, client.sentBodies.size)
    }

    @Test
    fun `if fallback request also fails, propagate the second exception`() {
        val client = stub(
            """{"error":"does not support tools"}""",
            """{"error":"backend unavailable"}""",
        )
        try {
            client.chat(listOf(LlmMessage("user", "hi")))
            fail("expected LlmProviderException after failed fallback")
        } catch (e: LlmProviderException) {
            assertTrue(
                e.message!!.contains("backend unavailable"),
                "must surface the fallback's failure, not swallow it: ${e.message}",
            )
        }
        assertEquals(2, client.sentBodies.size)
    }

    @Test
    fun `after first capability error, subsequent calls skip the native attempt`() {
        // The agentic loop chats many times per turn. Without the latch we'd burn
        // an extra HTTP roundtrip every time re-discovering the same incapability.
        val client = stub(
            """{"error":"does not support tools"}""",
            """{"message":{"content":"first"}}""",
            """{"message":{"content":"second"}}""",
            """{"message":{"content":"third"}}""",
        )
        client.chat(listOf(LlmMessage("user", "a")))
        client.chat(listOf(LlmMessage("user", "b")))
        client.chat(listOf(LlmMessage("user", "c")))

        // 1 (first attempt, errors) + 1 (fallback) + 1 (second call, native skipped) + 1 (third call, native skipped)
        assertEquals(4, client.sentBodies.size, "must not re-attempt native tools after a known capability rejection")
        // Every body except the very first should omit native tools.
        assertTrue(client.sentBodies[0].contains("\"tools\":["), "first request must try native tools")
        for (i in 1 until client.sentBodies.size) {
            assertFalse(client.sentBodies[i].contains("\"tools\":["),
                "request #$i should skip native tools after capability error")
        }
    }

    @Test
    fun `fallback returns plain text when model answers without calling a tool`() {
        // Model decided no tool needed — content is plain text, not a JSON tool call.
        val client = stub(
            """{"error":"does not support tools"}""",
            """{"message":{"content":"Hi there!"}}""",
        )
        val resp = client.chat(listOf(LlmMessage("user", "Say hi.")))
        assertTrue(resp is LlmResponse.Text)
        assertEquals("Hi there!", resp.content)
    }
}
