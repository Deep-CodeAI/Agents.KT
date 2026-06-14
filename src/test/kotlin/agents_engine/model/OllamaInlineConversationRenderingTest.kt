package agents_engine.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// #4513 — inline-mode models (no native tools) must see the conversation in the inline
// text they were taught, not native tool_calls / "tool"-role fields they can't read.
// withInlineToolPrompt re-renders the history accordingly, and the prompt now tells the
// model to STOP calling tools once it has a result (so it converges instead of looping).

class OllamaInlineConversationRenderingTest {

    private val greet = ToolDef("greet", "Greet a person by name") { it }
    private val client = OllamaClient(host = "localhost", port = 11434, model = "gemma3", tools = listOf(greet))

    @Test
    fun `tool-call and tool-result turns are re-rendered into inline text the model can read`() {
        val history = listOf(
            LlmMessage("user", "Greet Alice using the greet tool."),
            LlmMessage("assistant", "", toolCalls = listOf(ToolCall("greet", mapOf("name" to "Alice")))),
            LlmMessage("tool", "Hello, Alice!"),
        )

        val out = client.withInlineToolPrompt(history)

        assertEquals("system", out.first().role, "inline prompt prepended as system")
        assertTrue(out.none { it.role == "tool" }, "no native 'tool' role survives for an inline model")

        val assistant = out.single { it.role == "assistant" }
        assertTrue("\"tool\":\"greet\"" in assistant.content && "Alice" in assistant.content, assistant.content)
        assertTrue(assistant.toolCalls.isNullOrEmpty(), "native tool_calls dropped in favour of inline content")

        assertTrue(
            out.any { it.role == "user" && "Tool result: Hello, Alice!" in it.content },
            "tool result re-rendered as a readable user message: $out",
        )
    }

    @Test
    fun `the inline prompt tells the model to stop calling tools after a result`() {
        val prompt = client.buildInlineToolPrompt().lowercase()
        assertTrue("stop calling tools" in prompt, "must guide convergence: $prompt")
        assertTrue("plain text" in prompt)
        assertTrue("\"tool\"" in client.buildInlineToolPrompt(), "must keep the inline call format")
    }
}
