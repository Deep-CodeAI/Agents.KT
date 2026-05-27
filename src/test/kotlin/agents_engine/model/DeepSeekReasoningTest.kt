package agents_engine.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * #2409 — DeepSeek reasoning. By default thinking stays disabled (byte-identical
 * to prior behavior). When reasoning is opted in, the disable flag is dropped so
 * the reasoner emits `reasoning_content`, which the shared OpenAI-compatible
 * parse surfaces as `LlmResponse.reasoning`.
 */
class DeepSeekReasoningTest {

    @Test
    fun `thinking stays disabled by default, dropped when reasoning enabled`() {
        val msgs = listOf(LlmMessage("user", "hi"))

        val off = object : DeepSeekClient(apiKey = "k", model = "deepseek-chat") {}.buildRequestJson(msgs)
        assertTrue(""""thinking":{"type":"disabled"}""" in off, "default must keep thinking disabled: $off")

        val on = object : DeepSeekClient(apiKey = "k", model = "deepseek-reasoner", reasoning = ReasoningConfig()) {}
            .buildRequestJson(msgs)
        assertFalse("thinking" in on, "reasoning on must not disable thinking: $on")
    }

    @Test
    fun `reasoning_content is surfaced as reasoning via the shared parse`() {
        val body = """{"choices":[{"index":0,"message":{"role":"assistant","content":"42","reasoning_content":"divide then check"}}],"usage":{"prompt_tokens":1,"completion_tokens":1}}"""
        val text = assertIs<LlmResponse.Text>(
            object : DeepSeekClient(apiKey = "k", model = "deepseek-reasoner", reasoning = ReasoningConfig()) {}.parseResponse(body),
        )
        assertEquals("42", text.content)
        assertEquals("divide then check", text.reasoning)
    }
}
