package agents_engine.core

import agents_engine.model.LlmResponse
import agents_engine.model.ModelClient
import agents_engine.model.Tool
import agents_engine.model.ToolCall
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * #2757 — explicit audit signal for hallucinated tools. The LLM emitting a
 * tool name not in the skill's allowlist is a recoverable error (per #2476)
 * but a distinct audit event from "tool execution failed" or "policy denied
 * the tool." Auditors should be able to grep by event class.
 */
class ToolHallucinatedEventTest {

    private fun agentWithHallucinatingModel(): Agent<String, String> {
        val responses = ArrayDeque<LlmResponse>()
        // First turn: model emits a tool that doesn't exist on the skill
        responses.add(LlmResponse.ToolCalls(listOf(ToolCall("doesNotExist", mapOf("x" to 1)))))
        // Second turn: model recovers with a real tool call
        responses.add(LlmResponse.ToolCalls(listOf(ToolCall("real", emptyMap()))))
        // Third: done
        responses.add(LlmResponse.Text("done"))
        val mock = ModelClient { _ -> responses.removeFirst() }

        return agent<String, String>("a") {
            lateinit var real: Tool<Map<String, Any?>, Any?>
            model { ollama("test"); client = mock }
            tools { real = tool("real", "") { _ -> "ok" } }
            skills { skill<String, String>("s", "") { tools(real) } }
        }
    }

    @Test
    fun `unknown tool emits ToolHallucinated via onToolHallucinated listener`() {
        val captured = mutableListOf<Triple<String, Map<String, Any?>, List<String>>>()
        val a = agentWithHallucinatingModel()
        a.onToolHallucinated { name, args, allowed -> captured += Triple(name, args, allowed) }
        a("go")

        assertEquals(1, captured.size, "exactly one hallucination event")
        val (name, args, allowed) = captured.single()
        assertEquals("doesNotExist", name)
        assertEquals(1, args["x"], "arguments propagated")
        assertTrue("real" in allowed, "allowed list reflects skill's allowlist")
        assertTrue("doesNotExist" !in allowed, "the hallucinated name is not in the allowed list")
    }

    @Test
    fun `unknown tool emits PipelineEvent ToolHallucinated through observe()`() {
        val events = mutableListOf<PipelineEvent>()
        val a = agentWithHallucinatingModel()
        a.observe { events += it }
        a("go")

        val hall = events.filterIsInstance<PipelineEvent.ToolHallucinated>().single()
        assertEquals("doesNotExist", hall.requestedName)
        assertEquals("a", hall.agentName)
        assertNotNull(hall.runtimeContext.requestId, "runtime context carries requestId for correlation")
    }

    @Test
    fun `policy-denied tool does NOT emit ToolHallucinated (different event)`() {
        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.ToolCalls(listOf(ToolCall("real", emptyMap()))))
        responses.add(LlmResponse.Text("done"))
        val mock = ModelClient { _ -> responses.removeFirst() }

        val hallucinated = mutableListOf<String>()
        val denied = mutableListOf<String>()
        val a = agent<String, String>("a") {
            lateinit var real: Tool<Map<String, Any?>, Any?>
            model { ollama("test"); client = mock }
            tools { real = tool("real", "") { _ -> "ok" } }
            skills { skill<String, String>("s", "") { tools(real) } }
            onBeforeToolCall { _, _ -> Decision.Deny("not allowed") }
        }
        a.onToolHallucinated { name, _, _ -> hallucinated += name }
        a.onToolDenied { name, _, _ -> denied += name }
        a("go")

        assertEquals(emptyList(), hallucinated, "policy denial is not hallucination")
        assertEquals(listOf("real"), denied, "policy denial fires onToolDenied")
    }

    @Test
    fun `allowedTools does not leak the wider agent toolMap`() {
        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.ToolCalls(listOf(ToolCall("nope", emptyMap()))))
        responses.add(LlmResponse.Text("done"))
        val mock = ModelClient { _ -> responses.removeFirst() }

        var captured: List<String> = emptyList()
        val a = agent<String, String>("a") {
            lateinit var skillTool: Tool<Map<String, Any?>, Any?>
            lateinit var otherTool: Tool<Map<String, Any?>, Any?>
            model { ollama("test"); client = mock }
            tools {
                skillTool = tool("skillTool", "") { _ -> "ok" }
                otherTool = tool("otherTool", "") { _ -> "ok" }
            }
            skills {
                // 's' authorizes only skillTool; otherTool exists on the agent
                // but not on the skill — it must NOT appear in allowedTools.
                skill<String, String>("s", "") { tools(skillTool) }
            }
        }
        a.onToolHallucinated { _, _, allowed -> captured = allowed }
        a("go")

        assertTrue("skillTool" in captured)
        assertTrue("otherTool" !in captured, "allowedTools must NOT leak agent-level tools outside the skill")
    }
}
