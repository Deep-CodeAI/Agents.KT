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
 * #2410 — Ollama reasoning. With `reasoning` enabled the request carries
 * `think:true`; the model's reasoning arrives in `message.thinking` and is
 * surfaced as `LlmResponse.reasoning` / `LlmChunk.ReasoningDelta`, separate
 * from the answer. Off by default = byte-identical request to before.
 */
class OllamaReasoningTest {

    @Test
    fun `request carries think true only when reasoning is enabled`() {
        val msgs = listOf(LlmMessage("user", "hi"))
        val withReasoning = object : OllamaClient(model = "m", reasoning = ReasoningConfig()) {}
            .buildRequestJson(msgs)
        val without = object : OllamaClient(model = "m") {}.buildRequestJson(msgs)

        assertTrue(""""think":true""" in withReasoning, "reasoning on must send think:true: $withReasoning")
        assertFalse(""""think"""" in without, "reasoning off must not mention think: $without")
    }

    @Test
    fun `parseResponse surfaces message_thinking as reasoning, keeping content clean`() {
        val body = """{"message":{"role":"assistant","content":"42","thinking":"divide and check"},"done":true}"""
        val result = object : OllamaClient(model = "m") {}.parseResponse(body)

        val text = assertIs<LlmResponse.Text>(result)
        assertEquals("42", text.content)
        assertEquals("divide and check", text.reasoning)
    }

    @Test
    fun `parseResponse leaves reasoning null when no thinking field`() {
        val body = """{"message":{"role":"assistant","content":"plain"},"done":true}"""
        assertNull(assertIs<LlmResponse.Text>(object : OllamaClient(model = "m") {}.parseResponse(body)).reasoning)
    }

    @Test
    fun `chatStream emits ReasoningDelta from message_thinking ahead of the answer`() = runTest {
        val ndjson = buildString {
            appendLine("""{"message":{"role":"assistant","thinking":"let me think… ","content":""},"done":false}""")
            appendLine("""{"message":{"role":"assistant","thinking":"step two","content":""},"done":false}""")
            appendLine("""{"message":{"role":"assistant","content":"the answer"},"done":false}""")
            appendLine("""{"message":{"role":"assistant","content":""},"done":true,"prompt_eval_count":1,"eval_count":1}""")
        }
        val stub = object : OllamaClient(model = "test-model", reasoning = ReasoningConfig()) {
            override fun sendChatStream(body: String): java.io.InputStream = ndjson.byteInputStream(Charsets.UTF_8)
        }

        val chunks = stub.chatStream(listOf(LlmMessage("user", "Hi"))).toList()

        assertEquals(
            listOf("let me think… ", "step two"),
            chunks.filterIsInstance<LlmChunk.ReasoningDelta>().map { it.text },
        )
        assertEquals(
            listOf("the answer"),
            chunks.filterIsInstance<LlmChunk.TextDelta>().map { it.text },
        )
    }
}
