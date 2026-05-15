package agents_engine.model

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

// #1743 — non-live unit coverage for OpenAiClient.chatStream SSE parsing.
// OpenAI's SSE is `data:`-only (no `event:` names), terminated by the
// literal `data: [DONE]`. Tool calls correlate across deltas by
// `tool_calls[].index`; `id` arrives in the FIRST delta only.

class OpenAiClientChatStreamTest {

    @Test
    fun `text-only SSE stream emits TextDelta chunks plus End with usage from final delta`() = runTest {
        val sse = buildString {
            appendLine("""data: {"id":"x","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"role":"assistant","content":""},"finish_reason":null}]}""")
            appendLine()
            appendLine("""data: {"id":"x","choices":[{"index":0,"delta":{"content":"Hello"},"finish_reason":null}]}""")
            appendLine()
            appendLine("""data: {"id":"x","choices":[{"index":0,"delta":{"content":" world"},"finish_reason":null}]}""")
            appendLine()
            appendLine("""data: {"id":"x","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""")
            appendLine()
            appendLine("""data: {"id":"x","choices":[],"usage":{"prompt_tokens":11,"completion_tokens":6,"total_tokens":17}}""")
            appendLine()
            appendLine("""data: [DONE]""")
            appendLine()
        }

        val chunks = stubbedOpenAi(sse).chatStream(listOf(LlmMessage("user", "Hi"))).toList()

        assertEquals(3, chunks.size, "expected 2 TextDelta + End; got: $chunks")
        val d1 = chunks[0]; assertIs<LlmChunk.TextDelta>(d1); assertEquals("Hello", d1.text)
        val d2 = chunks[1]; assertIs<LlmChunk.TextDelta>(d2); assertEquals(" world", d2.text)
        val end = chunks[2]; assertIs<LlmChunk.End>(end)
        assertEquals(TokenUsage(promptTokens = 11, completionTokens = 6), end.tokenUsage)
    }

    @Test
    fun `tool-call SSE stream emits Started with call_id, ArgumentsDelta per chunk, Finished with parsed args`() = runTest {
        // The id only arrives in the first delta; subsequent deltas
        // correlate via tool_calls[].index. Aggregator must remember the
        // id across deltas.
        val sse = buildString {
            appendLine("""data: {"id":"x","choices":[{"index":0,"delta":{"role":"assistant","tool_calls":[{"index":0,"id":"call_abc","type":"function","function":{"name":"get_weather","arguments":""}}]},"finish_reason":null}]}""")
            appendLine()
            appendLine("""data: {"id":"x","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"location"}}]},"finish_reason":null}]}""")
            appendLine()
            appendLine("""data: {"id":"x","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\":\"SF\"}"}}]},"finish_reason":null}]}""")
            appendLine()
            appendLine("""data: {"id":"x","choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}""")
            appendLine()
            appendLine("""data: {"id":"x","choices":[],"usage":{"prompt_tokens":42,"completion_tokens":18,"total_tokens":60}}""")
            appendLine()
            appendLine("""data: [DONE]""")
            appendLine()
        }

        val chunks = stubbedOpenAi(sse).chatStream(listOf(LlmMessage("user", "weather"))).toList()

        val started = chunks.filterIsInstance<LlmChunk.ToolCallStarted>().single()
        assertEquals("call_abc", started.callId, "callId must be OpenAI's call_* id from the first delta")
        assertEquals("get_weather", started.toolName)

        val deltas = chunks.filterIsInstance<LlmChunk.ToolCallArgumentsDelta>().filter { it.callId == "call_abc" }
        // Three argument-bearing deltas: the initial empty arguments string,
        // then two non-empty fragments. Aggregator may or may not skip the
        // empty one; we accept either shape but assert the non-empty deltas
        // appear with the right content.
        val deltaJsons = deltas.map { it.deltaJson }
        assertTrue("""{"location""" in deltaJsons, "expected first non-empty args fragment; got deltas: $deltaJsons")
        assertTrue("""":"SF"}""" in deltaJsons, "expected second non-empty args fragment; got deltas: $deltaJsons")

        val finished = chunks.filterIsInstance<LlmChunk.ToolCallFinished>().single()
        assertEquals("call_abc", finished.callId)
        assertEquals(mapOf("location" to "SF"), finished.arguments)

        val end = chunks.filterIsInstance<LlmChunk.End>().single()
        assertEquals(TokenUsage(promptTokens = 42, completionTokens = 18), end.tokenUsage)
    }

    private fun stubbedOpenAi(sse: String): OpenAiClient =
        object : OpenAiClient(apiKey = "test-key", model = "test-model") {
            override fun sendChatStream(body: String, headers: Map<String, String>): java.io.InputStream =
                sse.byteInputStream(Charsets.UTF_8)
        }
}
