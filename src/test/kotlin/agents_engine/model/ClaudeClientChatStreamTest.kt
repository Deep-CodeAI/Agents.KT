package agents_engine.model

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

// #1742 — non-live unit coverage for ClaudeClient.chatStream SSE parsing.
// Subclass overrides sendChatStream to inject hardcoded SSE; assertions
// pin the chunk sequence. Anthropic-specific concerns:
//   - content_block index correlates chunks to a block
//   - tool_use blocks carry the canonical Anthropic id (toolu_*) which
//     becomes LlmChunk.ToolCallStarted.callId verbatim
//   - input_json_delta carries partial JSON, summed to a full args object
//     at content_block_stop
//   - usage straddles message_start (input_tokens) and message_delta
//     (output_tokens running total), bundled into End at message_stop
class ClaudeClientChatStreamTest {

    @Test
    fun `text-only SSE response emits TextDelta chunks plus End with combined token usage`() = runTest {
        val sse = """
            event: message_start
            data: {"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","content":[],"model":"claude-haiku","stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":12,"output_tokens":1}}}

            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello"}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":" world"}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":0}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":7}}

            event: message_stop
            data: {"type":"message_stop"}

        """.trimIndent()
        val chunks = stubbedClaude(sse).chatStream(listOf(LlmMessage("user", "Hi"))).toList()

        assertEquals(3, chunks.size, "expected 2 TextDelta + End; got: $chunks")
        val d0 = chunks[0]; assertIs<LlmChunk.TextDelta>(d0); assertEquals("Hello", d0.text)
        val d1 = chunks[1]; assertIs<LlmChunk.TextDelta>(d1); assertEquals(" world", d1.text)
        val end = chunks[2]; assertIs<LlmChunk.End>(end)
        assertEquals(TokenUsage(promptTokens = 12, completionTokens = 7), end.tokenUsage)
    }

    @Test
    fun `tool_use SSE emits Started with toolu_ id, ArgumentsDelta per partial_json, Finished with parsed args`() = runTest {
        val sse = """
            event: message_start
            data: {"type":"message_start","message":{"id":"msg_2","usage":{"input_tokens":40,"output_tokens":1}}}

            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_01ABC","name":"get_weather","input":{}}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"location\":\"S"}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"F\"}"}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":0}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":18}}

            event: message_stop
            data: {"type":"message_stop"}

        """.trimIndent()
        val chunks = stubbedClaude(sse).chatStream(listOf(LlmMessage("user", "weather"))).toList()

        // Expected: ToolCallStarted, ArgumentsDelta×2, ToolCallFinished, End.
        assertEquals(5, chunks.size, "expected tool-call triple (with 2 args deltas) + End; got: $chunks")
        val started = chunks[0]; assertIs<LlmChunk.ToolCallStarted>(started)
        assertEquals("toolu_01ABC", started.callId, "callId must be Anthropic's tool_use.id, not a synthesized one")
        assertEquals("get_weather", started.toolName)

        val d1 = chunks[1]; assertIs<LlmChunk.ToolCallArgumentsDelta>(d1)
        assertEquals("toolu_01ABC", d1.callId)
        assertEquals("""{"location":"S""", d1.deltaJson)

        val d2 = chunks[2]; assertIs<LlmChunk.ToolCallArgumentsDelta>(d2)
        assertEquals("toolu_01ABC", d2.callId)
        assertEquals("""F"}""", d2.deltaJson)

        val finished = chunks[3]; assertIs<LlmChunk.ToolCallFinished>(finished)
        assertEquals("toolu_01ABC", finished.callId)
        assertEquals(mapOf("location" to "SF"), finished.arguments)

        val end = chunks[4]; assertIs<LlmChunk.End>(end)
        assertEquals(TokenUsage(promptTokens = 40, completionTokens = 18), end.tokenUsage)
    }

    @Test
    fun `interleaved text and tool_use blocks emit correctly keyed by callId`() = runTest {
        // Two content blocks (index 0 = text, index 1 = tool_use) with deltas
        // interleaved by index. The aggregator must dispatch each delta to
        // the right block by index, and use the right callId.
        val sse = """
            event: message_start
            data: {"type":"message_start","message":{"id":"msg_3","usage":{"input_tokens":50,"output_tokens":1}}}

            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

            event: content_block_start
            data: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_XYZ","name":"calc","input":{}}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"thinking..."}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"x\":1}"}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":0}

            event: content_block_stop
            data: {"type":"content_block_stop","index":1}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":12}}

            event: message_stop
            data: {"type":"message_stop"}

        """.trimIndent()
        val chunks = stubbedClaude(sse).chatStream(listOf(LlmMessage("user", "go"))).toList()

        val textDelta = chunks.filterIsInstance<LlmChunk.TextDelta>().single()
        assertEquals("thinking...", textDelta.text)

        val started = chunks.filterIsInstance<LlmChunk.ToolCallStarted>().single()
        assertEquals("toolu_XYZ", started.callId)
        assertEquals("calc", started.toolName)

        val argsDelta = chunks.filterIsInstance<LlmChunk.ToolCallArgumentsDelta>().single()
        assertEquals("toolu_XYZ", argsDelta.callId, "tool-args delta must be tagged with the tool_use block's callId, not the text block")
        assertEquals("""{"x":1}""", argsDelta.deltaJson)

        val finished = chunks.filterIsInstance<LlmChunk.ToolCallFinished>().single()
        assertEquals("toolu_XYZ", finished.callId)
        assertEquals(mapOf("x" to 1), finished.arguments)

        val end = chunks.filterIsInstance<LlmChunk.End>().single()
        assertEquals(TokenUsage(promptTokens = 50, completionTokens = 12), end.tokenUsage)

        // Strict ordering proof: text delta before tool_use delta in the wire,
        // so first TextDelta arrives at index < first ToolCallArgumentsDelta.
        val textIdx = chunks.indexOf(textDelta)
        val argsIdx = chunks.indexOf(argsDelta)
        assertTrue(textIdx < argsIdx, "TextDelta(index=0) arrived before ArgumentsDelta(index=1) per wire order")
    }

    private fun stubbedClaude(sse: String): ClaudeClient =
        object : ClaudeClient(apiKey = "test-key", model = "test-model") {
            override fun sendChatStream(body: String, headers: Map<String, String>): java.io.InputStream =
                sse.byteInputStream(Charsets.UTF_8)
        }
}
