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
 * #2411 — OpenAI reasoning. Chat Completions takes `reasoning_effort` and
 * reports `reasoning_tokens` (a subset of completion tokens) but returns NO
 * reasoning text. The shared OpenAI-compatible parse also surfaces
 * `reasoning_content` when a gateway provides it (none for OpenAI proper).
 */
class OpenAiReasoningTest {

    @Test
    fun `request carries reasoning_effort only when reasoning with an effort is set`() {
        val on = object : OpenAiClient(apiKey = "k", model = "o3", reasoning = ReasoningConfig(effort = ReasoningEffort.HIGH)) {}
            .buildRequestJson(listOf(LlmMessage("user", "hi")))
        assertTrue(""""reasoning_effort":"high"""" in on, on)

        val off = object : OpenAiClient(apiKey = "k", model = "gpt-4o") {}
            .buildRequestJson(listOf(LlmMessage("user", "hi")))
        assertFalse("reasoning_effort" in off, off)
    }

    @Test
    fun `reasoning_tokens from usage are surfaced on TokenUsage`() {
        val body = """{"choices":[{"index":0,"message":{"role":"assistant","content":"42"}}],"usage":{"prompt_tokens":10,"completion_tokens":50,"completion_tokens_details":{"reasoning_tokens":40}}}"""
        val result = object : OpenAiClient(apiKey = "k", model = "o3") {}.parseResponse(body)
        assertEquals(40, result.tokenUsage?.reasoningTokens)
        // OpenAI proper sends no reasoning text.
        assertNull(assertIs<LlmResponse.Text>(result).reasoning)
    }

    @Test
    fun `shared parse surfaces reasoning_content when present (OpenAI-compatible gateways)`() {
        val body = """{"choices":[{"index":0,"message":{"role":"assistant","content":"answer","reasoning_content":"step by step"}}],"usage":{"prompt_tokens":1,"completion_tokens":1}}"""
        val text = assertIs<LlmResponse.Text>(object : OpenAiClient(apiKey = "k", model = "m") {}.parseResponse(body))
        assertEquals("answer", text.content)
        assertEquals("step by step", text.reasoning)
    }

    @Test
    fun `chatStream emits ReasoningDelta from delta_reasoning_content ahead of the answer`() = runTest {
        val sse = buildString {
            appendLine("""data: {"choices":[{"index":0,"delta":{"reasoning_content":"think "},"finish_reason":null}]}""")
            appendLine()
            appendLine("""data: {"choices":[{"index":0,"delta":{"reasoning_content":"more"},"finish_reason":null}]}""")
            appendLine()
            appendLine("""data: {"choices":[{"index":0,"delta":{"content":"answer"},"finish_reason":null}]}""")
            appendLine()
            appendLine("""data: {"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""")
            appendLine()
            appendLine("""data: {"choices":[],"usage":{"prompt_tokens":1,"completion_tokens":1}}""")
            appendLine()
            appendLine("""data: [DONE]""")
            appendLine()
        }
        val stub = object : OpenAiClient(apiKey = "k", model = "m", reasoning = ReasoningConfig()) {
            override fun sendChatStream(body: String, headers: Map<String, String>): java.io.InputStream =
                sse.byteInputStream(Charsets.UTF_8)
        }

        val chunks = stub.chatStream(listOf(LlmMessage("user", "Hi"))).toList()
        assertEquals(listOf("think ", "more"), chunks.filterIsInstance<LlmChunk.ReasoningDelta>().map { it.text })
        assertEquals(listOf("answer"), chunks.filterIsInstance<LlmChunk.TextDelta>().map { it.text })
    }
}
