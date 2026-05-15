package agents_engine.model

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

// #1722 — TDD red-first for the v0.5.0 streaming foundation.
//
// The default `chatStream` implementation on ModelClient must turn a single
// non-streaming response into an ordered chunk sequence equivalent to what
// a streaming provider would emit. That gives consumers a uniform Flow
// surface regardless of whether the underlying provider streams natively.
//
// These tests pin the chunk-sequence contract. They drive the design:
// implementing chatStream must emit exactly these chunks, in exactly this
// order, for these two response shapes.

class ModelClientChatStreamDefaultTest {

    @Test
    fun `default chatStream emits TextDelta then End for a Text response`() = runTest {
        val usage = TokenUsage(promptTokens = 12, completionTokens = 5)
        val client = ModelClient { _ -> LlmResponse.Text("hello world", usage) }

        val chunks = client.chatStream(emptyList()).toList()

        assertEquals(2, chunks.size, "expected exactly [TextDelta, End]; got: $chunks")
        val first = chunks[0]
        assertIs<LlmChunk.TextDelta>(first)
        assertEquals("hello world", first.text)
        val second = chunks[1]
        assertIs<LlmChunk.End>(second)
        assertEquals(usage, second.tokenUsage)
    }

    @Test
    fun `default chatStream emits ToolCall lifecycle then End for a ToolCalls response`() = runTest {
        val usage = TokenUsage(promptTokens = 20, completionTokens = 8)
        val call = ToolCall(
            name = "lookup_customer",
            arguments = mapOf("id" to 42, "verbose" to true),
            rawArguments = """{"id":42,"verbose":true}""",
        )
        val client = ModelClient { _ -> LlmResponse.ToolCalls(listOf(call), usage) }

        val chunks = client.chatStream(emptyList()).toList()

        // Expected ordered sequence per call: Started → ArgumentsDelta → Finished.
        // Then a single End with the token usage.
        assertEquals(4, chunks.size, "expected [Started, ArgsDelta, Finished, End]; got: $chunks")

        val started = chunks[0]
        assertIs<LlmChunk.ToolCallStarted>(started)
        assertEquals("lookup_customer", started.toolName)
        assertNotNull(started.callId, "callId must be synthesized when not provided by the response")

        val delta = chunks[1]
        assertIs<LlmChunk.ToolCallArgumentsDelta>(delta)
        assertEquals(started.callId, delta.callId, "ArgumentsDelta must share callId with Started")
        assertEquals("""{"id":42,"verbose":true}""", delta.deltaJson)

        val finished = chunks[2]
        assertIs<LlmChunk.ToolCallFinished>(finished)
        assertEquals(started.callId, finished.callId, "Finished must share callId with Started")
        assertEquals(mapOf("id" to 42, "verbose" to true), finished.arguments)

        val end = chunks[3]
        assertIs<LlmChunk.End>(end)
        assertEquals(usage, end.tokenUsage)
    }
}
