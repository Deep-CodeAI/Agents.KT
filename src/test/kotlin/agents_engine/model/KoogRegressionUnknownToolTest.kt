package agents_engine.model

import agents_engine.core.agent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * #2476 (under Koog regression epic #2474) — when the LLM mid-loop emits a
 * tool name absent from the skill's allowed set, the framework MUST
 * surface a recoverable error message back to the model (appended as a
 * tool result), NOT throw and kill the loop.
 *
 * Construction-time guards already reject unknown tools at build time
 * (#631 / #630 / #645). The runtime path is the Koog regression target:
 * the model can hallucinate a tool name that exists on the agent but not
 * for this skill, or a name that doesn't exist at all. Either way the
 * loop must keep running so the model can retry with a correct tool.
 */
class KoogRegressionUnknownToolTest {

    @Test
    fun `unknown tool name mid-loop is recoverable — error appended to context and the loop continues`() {
        // Turn 1: model calls "doesntExist". Loop must NOT throw; instead,
        // it appends a tool-result message describing the error and loops
        // back for turn 2.
        // Turn 2: model calls the real tool, "real". This must succeed.
        // Turn 3: model returns text. Loop exits with that text.
        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.ToolCalls(listOf(ToolCall(name = "doesntExist", arguments = emptyMap()))))
        responses.add(LlmResponse.ToolCalls(listOf(ToolCall(name = "real", arguments = emptyMap()))))
        responses.add(LlmResponse.Text("done"))

        var seenOnTurn3: List<LlmMessage>? = null
        var turnIndex = 0
        val mock = ModelClient { messages ->
            turnIndex++
            if (turnIndex == 3) seenOnTurn3 = messages.toList()
            responses.removeFirst()
        }

        var realCalls = 0
        val a = agent<String, String>("a") {
            lateinit var real: Tool<Map<String, Any?>, Any?>
            model { ollama("llama3"); client = mock }
            tools {
                real = tool("real", "the real tool") { _ -> realCalls++; "ok" }
            }
            skills { skill<String, String>("s", "stub") { tools(real) } }
        }

        val out = a("input")

        assertEquals("done", out, "loop must reach completion after an unknown-tool recovery")
        assertEquals(1, realCalls, "the real tool runs exactly once after the recovery")

        // The model on its third turn must see a tool-result message
        // explaining the unknown-tool failure — that's what makes the
        // recovery actionable.
        val msgs = seenOnTurn3 ?: fail("the loop must reach a third LLM turn after recovery")
        val toolMessages = msgs.filter { it.role == "tool" }
        assertTrue(toolMessages.isNotEmpty(), "at least one tool-result message must have been appended")
        val errorMessage = toolMessages.firstOrNull { it.content.contains("doesntExist", ignoreCase = true) }
            ?: fail("expected a tool message naming the unknown tool 'doesntExist'; got: ${toolMessages.map { it.content }}")
        assertTrue(
            errorMessage.content.contains("real", ignoreCase = true) ||
                errorMessage.content.contains("allowed", ignoreCase = true) ||
                errorMessage.content.contains("available", ignoreCase = true) ||
                errorMessage.content.contains("unknown", ignoreCase = true),
            "the error message must guide the model toward the allowed tool set; got: ${errorMessage.content}",
        )
    }
}
