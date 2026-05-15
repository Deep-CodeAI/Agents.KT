package agents_engine.model

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

// #1741 — non-live unit coverage for OllamaClient.chatStream native streaming.
// Subclasses the client to inject a known NDJSON payload through the
// `sendChatStream` test seam, asserts the chunk sequence the parser emits.
// Live integration coverage lives separately in
// `OllamaClientChatStreamLiveTest.kt` (`@Tag("live-llm")`).

class OllamaClientChatStreamTest {

    @Test
    fun `chatStream parses NDJSON content chunks into TextDelta plus End with token usage`() = runTest {
        // Three intermediate chunks + final done chunk with prompt + eval counts.
        // Lines are NDJSON — one JSON object per line, separated by '\n'.
        val ndjson = buildString {
            appendLine("""{"model":"llama","message":{"role":"assistant","content":"Hello "},"done":false}""")
            appendLine("""{"model":"llama","message":{"role":"assistant","content":"streaming "},"done":false}""")
            appendLine("""{"model":"llama","message":{"role":"assistant","content":"world"},"done":false}""")
            appendLine("""{"model":"llama","message":{"role":"assistant","content":""},"done":true,"prompt_eval_count":12,"eval_count":8}""")
        }
        val stubbed = stubbedOllama(ndjson)

        val chunks = stubbed.chatStream(listOf(LlmMessage("user", "Hi"))).toList()

        assertEquals(4, chunks.size, "expected 3 TextDelta + 1 End; got: $chunks")
        val d0 = chunks[0]; assertIs<LlmChunk.TextDelta>(d0); assertEquals("Hello ", d0.text)
        val d1 = chunks[1]; assertIs<LlmChunk.TextDelta>(d1); assertEquals("streaming ", d1.text)
        val d2 = chunks[2]; assertIs<LlmChunk.TextDelta>(d2); assertEquals("world", d2.text)
        val end = chunks[3]; assertIs<LlmChunk.End>(end)
        assertEquals(TokenUsage(promptTokens = 12, completionTokens = 8), end.tokenUsage)
    }

    @Test
    fun `chatStream emits ToolCallStarted, ArgumentsDelta, and Finished for tool-call response then End`() = runTest {
        // Ollama bundles tool calls into the final chunk (done:true) — not progressively streamed.
        // The override emits the canonical Started/ArgumentsDelta/Finished triple per call.
        val ndjson = buildString {
            appendLine("""{"model":"llama","message":{"role":"assistant","content":""},"done":false}""")
            appendLine("""{"model":"llama","message":{"role":"assistant","content":"","tool_calls":[{"function":{"name":"lookup","arguments":{"id":42}}}]},"done":true,"prompt_eval_count":20,"eval_count":3}""")
        }
        val stubbed = stubbedOllama(ndjson)

        val chunks = stubbed.chatStream(listOf(LlmMessage("user", "Look up 42"))).toList()

        // Empty TextDelta from first line is skipped per implementation (we only emit non-empty text).
        // Expected: ToolCallStarted, ArgumentsDelta, ToolCallFinished, End.
        assertEquals(4, chunks.size, "expected tool-call triple + End; got: $chunks")
        val started = chunks[0]; assertIs<LlmChunk.ToolCallStarted>(started); assertEquals("lookup", started.toolName)
        val delta = chunks[1]; assertIs<LlmChunk.ToolCallArgumentsDelta>(delta)
        assertEquals(started.callId, delta.callId, "ArgumentsDelta must share callId with Started")
        val finished = chunks[2]; assertIs<LlmChunk.ToolCallFinished>(finished)
        assertEquals(started.callId, finished.callId, "Finished must share callId with Started")
        assertEquals(mapOf("id" to 42), finished.arguments)
        val end = chunks[3]; assertIs<LlmChunk.End>(end)
        assertEquals(TokenUsage(promptTokens = 20, completionTokens = 3), end.tokenUsage)
    }

    @Test
    fun `chatStream skips empty content chunks so consumers don't see no-op TextDeltas`() = runTest {
        // Some Ollama models emit blank intermediate chunks. They mustn't produce TextDelta events.
        val ndjson = buildString {
            appendLine("""{"model":"llama","message":{"role":"assistant","content":""},"done":false}""")
            appendLine("""{"model":"llama","message":{"role":"assistant","content":"only this"},"done":false}""")
            appendLine("""{"model":"llama","message":{"role":"assistant","content":""},"done":true,"prompt_eval_count":5,"eval_count":2}""")
        }
        val stubbed = stubbedOllama(ndjson)

        val chunks = stubbed.chatStream(listOf(LlmMessage("user", "Hi"))).toList()

        val textDeltas = chunks.filterIsInstance<LlmChunk.TextDelta>()
        assertEquals(1, textDeltas.size, "expected exactly one TextDelta; got: $textDeltas")
        assertEquals("only this", textDeltas.single().text)
        assertTrue(chunks.last() is LlmChunk.End)
    }

    /**
     * Test seam: subclass OllamaClient and override the new `sendChatStream`
     * method to return a hardcoded NDJSON string. Mirrors the existing
     * `sendChat` test seam used by `OllamaClientResponseSizeLimitTest` etc.
     */
    private fun stubbedOllama(ndjson: String): OllamaClient = object : OllamaClient(model = "test-model") {
        override fun sendChatStream(body: String): java.io.InputStream =
            ndjson.byteInputStream(Charsets.UTF_8)
    }
}
