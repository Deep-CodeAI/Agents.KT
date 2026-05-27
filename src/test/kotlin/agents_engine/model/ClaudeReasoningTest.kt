package agents_engine.model

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #2408 — Claude extended thinking. With reasoning enabled the request carries
 * `thinking:{type:enabled,budget_tokens}` and forces temperature=1 (Anthropic
 * constraint); thinking blocks / `thinking_delta` surface as reasoning,
 * separate from the answer. Off by default = unchanged request.
 */
class ClaudeReasoningTest {

    @Test
    fun `request enables thinking and forces temperature 1 only when reasoning is on`() {
        val on = object : ClaudeClient(apiKey = "k", model = "m", reasoning = ReasoningConfig(budgetTokens = 2048)) {}
            .buildRequestJson(listOf(LlmMessage("user", "hi")))
        assertTrue(""""thinking":{"type":"enabled","budget_tokens":2048}""" in on, on)
        assertTrue(""""temperature":1.0""" in on, "thinking forces temperature=1: $on")

        val off = object : ClaudeClient(apiKey = "k", model = "m", temperature = 0.7) {}
            .buildRequestJson(listOf(LlmMessage("user", "hi")))
        assertFalse("thinking" in off, "no thinking field when off: $off")
        assertTrue(""""temperature":0.7""" in off, off)
    }

    @Test
    fun `parseResponse extracts thinking blocks as reasoning, keeping text clean`() {
        val body = """{"content":[{"type":"thinking","thinking":"let me reason"},{"type":"text","text":"answer"}],"usage":{"input_tokens":1,"output_tokens":1}}"""
        val text = assertIs<LlmResponse.Text>(object : ClaudeClient(apiKey = "k", model = "m") {}.parseResponse(body))
        assertEquals("answer", text.content)
        assertEquals("let me reason", text.reasoning)
    }

    @Test
    fun `parseResponse leaves reasoning null without thinking blocks`() {
        val body = """{"content":[{"type":"text","text":"plain"}],"usage":{"input_tokens":1,"output_tokens":1}}"""
        assertNull(assertIs<LlmResponse.Text>(object : ClaudeClient(apiKey = "k", model = "m") {}.parseResponse(body)).reasoning)
    }

    @Test
    fun `chatStream emits ReasoningDelta from thinking_delta ahead of the answer`() = runTest {
        val sse = """
            event: message_start
            data: {"type":"message_start","message":{"id":"m1","usage":{"input_tokens":3,"output_tokens":1}}}

            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"thinking"}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"hmm "}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"ok"}}

            event: content_block_start
            data: {"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"answer"}}

            event: message_stop
            data: {"type":"message_stop"}

        """.trimIndent()
        val stub = object : ClaudeClient(apiKey = "k", model = "m", reasoning = ReasoningConfig()) {
            override fun sendChatStream(body: String, headers: Map<String, String>): java.io.InputStream =
                sse.byteInputStream(Charsets.UTF_8)
        }

        val chunks = stub.chatStream(listOf(LlmMessage("user", "Hi"))).toList()
        assertEquals(listOf("hmm ", "ok"), chunks.filterIsInstance<LlmChunk.ReasoningDelta>().map { it.text })
        assertEquals(listOf("answer"), chunks.filterIsInstance<LlmChunk.TextDelta>().map { it.text })
    }
}
