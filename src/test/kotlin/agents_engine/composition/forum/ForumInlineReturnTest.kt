package agents_engine.composition.forum

import agents_engine.core.agent
import agents_engine.model.LlmResponse
import agents_engine.model.ModelClient
import kotlin.test.Test
import kotlin.test.assertEquals

// #4514 — a captain may emit the forum_return CALL as inline JSON text instead of a real
// tool call (so no ForumReturnException fires). The forum must still recognise it and return
// the extracted value, not leak the raw tool-call JSON.

class ForumInlineReturnTest {

    private fun participant() = agent<String, String>("p") {
        skills { skill<String, String>("p") { implementedBy { "participant-said-$it" } } }
    }

    /** Captain whose model replies with [text] as plain content (no native tool call). */
    private fun captainSaying(text: String) = agent<String, String>("c") {
        model {
            ollama("test")
            client = ModelClient { _ -> LlmResponse.Text(text) }
        }
        skills { skill<String, String>("c") { tools() } }
    }

    private fun runForum(captainText: String): String =
        forum<String, String> {
            participant(participant())
            captain(captainSaying(captainText))
        }("topic")

    @Test
    fun `inline forum_return in the OpenAI name shape is parsed, not leaked`() {
        // {"name":"forum_return", ...} — exactly what qwen3-vl emitted live.
        val verdict = """{"name":"forum_return","arguments":{"value":"the answer is 108"}}"""
        assertEquals("the answer is 108", runForum(verdict))
    }

    @Test
    fun `inline forum_return in the tool shape is parsed`() {
        assertEquals("done", runForum("""{"tool":"forum_return","arguments":{"value":"done"}}"""))
    }

    @Test
    fun `a single-argument inline forum_return uses that argument`() {
        assertEquals("blue", runForum("""{"name":"forum_return","arguments":{"answer":"blue"}}"""))
    }

    @Test
    fun `a plain-text verdict is returned unchanged`() {
        assertEquals("the final answer is blue", runForum("the final answer is blue"))
    }

    @Test
    fun `a non-forum_return JSON verdict is not mistaken for a return`() {
        val json = """{"name":"some_other_tool","arguments":{"x":1}}"""
        assertEquals(json, runForum(json))
    }
}
