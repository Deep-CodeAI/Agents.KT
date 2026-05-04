package agents_engine.model

import agents_engine.core.MemoryBank
import agents_engine.core.agent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Tests for the per-skill tool allowlist enforcement (issue #630).
 *
 * The bug: execution previously looked up against the full agent.toolMap,
 * meaning a skill could indirectly call any tool registered globally on the
 * agent — even tools not in its `tools(...)` list.
 *
 * The fix: execution must look up against the per-invocation allowlist
 * (skill-declared + auto + memory + knowledge tools).
 */
class ToolAuthorizationTest {

    @Test
    fun `model emitting an unlisted tool name is rejected with a clear error naming the skill`() {
        var dangerousExecuted = false
        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.ToolCalls(listOf(ToolCall(name = "dangerousTool", arguments = emptyMap()))))
        responses.add(LlmResponse.Text("done"))
        val mock = ModelClient { _ -> responses.removeFirst() }

        val a = agent<String, String>("guarded") {
            lateinit var safeTool: Tool<Map<String, Any?>, Any?>
            model { ollama("llama3"); client = mock }
            tools {
                safeTool = tool("safeTool", "ok") { _ -> "safe-result" }
                tool("dangerousTool", "danger") { _ -> dangerousExecuted = true; "should-not-run" }
            }
            // Skill only allows safeTool; dangerousTool exists on the agent but not in this skill's allowlist
            skills { skill<String, String>("only-safe", "stub") { tools(safeTool) } }
        }

        try {
            a("input")
            fail("expected runtime to refuse execution of unlisted tool")
        } catch (e: Throwable) {
            assertFalse(dangerousExecuted, "dangerousTool must NOT have run")
            val msg = e.message ?: e.toString()
            assertTrue(msg.contains("dangerousTool"), "error must name the offending tool, got: $msg")
            assertTrue(
                msg.contains("only-safe", ignoreCase = true) || msg.contains("not allowed"),
                "error must mention the skill or 'not allowed', got: $msg",
            )
        }
    }

    @Test
    fun `model emitting an entirely unknown tool name still throws`() {
        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.ToolCalls(listOf(ToolCall(name = "doesNotExistAnywhere", arguments = emptyMap()))))
        responses.add(LlmResponse.Text("done"))
        val mock = ModelClient { _ -> responses.removeFirst() }

        val a = agent<String, String>("a") {
            lateinit var safeTool: Tool<Map<String, Any?>, Any?>
            model { ollama("llama3"); client = mock }
            tools { safeTool = tool("safeTool", "ok") { _ -> "ok" } }
            skills { skill<String, String>("s", "stub") { tools(safeTool) } }
        }

        try {
            a("input")
            fail("expected runtime to refuse execution of unknown tool")
        } catch (e: Throwable) {
            assertTrue((e.message ?: "").contains("doesNotExistAnywhere"))
        }
    }

    @Test
    fun `error message does NOT leak the wider agent toolmap`() {
        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.ToolCalls(listOf(ToolCall(name = "secretTool", arguments = emptyMap()))))
        responses.add(LlmResponse.Text("done"))
        val mock = ModelClient { _ -> responses.removeFirst() }

        val a = agent<String, String>("a") {
            lateinit var publicTool: Tool<Map<String, Any?>, Any?>
            model { ollama("llama3"); client = mock }
            tools {
                publicTool = tool("publicTool", "") { _ -> "ok" }
                tool("secretTool", "") { _ -> "should-stay-hidden" }
                tool("anotherSecretTool", "") { _ -> "also-hidden" }
            }
            // Only publicTool allowed for this skill
            skills { skill<String, String>("s", "stub") { tools(publicTool) } }
        }

        try {
            a("input")
            fail("expected refusal")
        } catch (e: Throwable) {
            val msg = e.message ?: ""
            assertFalse(
                msg.contains("anotherSecretTool"),
                "error must not enumerate tools the skill is not allowed to know about: $msg",
            )
        }
    }

    @Test
    fun `memory tools remain callable when memory is configured`() {
        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.ToolCalls(listOf(ToolCall(name = "memory_read", arguments = mapOf("key" to "x")))))
        responses.add(LlmResponse.Text("done"))
        val mock = ModelClient { _ -> responses.removeFirst() }

        val bank = MemoryBank()
        bank.write("a", "stored")
        val a = agent<String, String>("memTest") {
            model { ollama("llama3"); client = mock }
            memory(bank)
            skills { skill<String, String>("s", "stub") { tools() /* no specific tools, but memory should still work */ } }
        }

        // Should NOT throw — memory tools are auto-injected into the allowlist when memory is configured
        val result = a("input")
        assertEquals("done", result)
    }

    @Test
    fun `knowledge tools remain callable when knowledge is declared`() {
        var knowledgeRead = false
        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.ToolCalls(listOf(ToolCall(name = "guide", arguments = emptyMap()))))
        responses.add(LlmResponse.Text("done"))
        val mock = ModelClient { _ -> responses.removeFirst() }

        val a = agent<String, String>("kTest") {
            model { ollama("llama3"); client = mock }
            skills { skill<String, String>("s", "stub") {
                tools()
                knowledge("guide", "rules") { knowledgeRead = true; "rule one" }
            } }
        }

        a("input")
        assertTrue(knowledgeRead, "knowledge tool must remain in the allowlist")
    }

    @Test
    fun `two skills on one agent have disjoint allowlists - cross-call refused`() {
        var skillBToolExecuted = false
        val responses = ArrayDeque<LlmResponse>()
        // Model running under skill-A tries to call a tool that only skill-B declares
        responses.add(LlmResponse.ToolCalls(listOf(ToolCall(name = "bOnly", arguments = emptyMap()))))
        responses.add(LlmResponse.Text("done"))
        val mock = ModelClient { _ -> responses.removeFirst() }

        val a = agent<String, String>("twoSkills") {
            lateinit var aOnly: Tool<Map<String, Any?>, Any?>
            lateinit var bOnly: Tool<Map<String, Any?>, Any?>
            model { ollama("llama3"); client = mock }
            tools {
                aOnly = tool("aOnly", "") { _ -> "a-result" }
                bOnly = tool("bOnly", "") { _ -> skillBToolExecuted = true; "should-not-run-from-A" }
            }
            skills {
                skill<String, String>("skill-A", "stub") { tools(aOnly) }
                skill<String, String>("skill-B", "stub") { tools(bOnly) }
            }
            // Force skill-A so the LLM is running under skill-A's allowlist
            skillSelection { _ -> "skill-A" }
        }

        try {
            a("input")
            fail("expected refusal — bOnly is not in skill-A's allowlist")
        } catch (e: Throwable) {
            assertFalse(skillBToolExecuted, "skill-A must not be able to call skill-B's tools")
            assertTrue((e.message ?: "").contains("bOnly"))
        }
    }
}
