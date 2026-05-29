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
 * The fix: execution looks up against the per-invocation allowlist
 * (skill-declared + auto + memory + knowledge tools).
 *
 * #2476 — when the model emits a name NOT in that allowlist (unlisted or
 * outright unknown), the runtime appends a clear tool-result error to the
 * conversation and the loop CONTINUES, so the model can self-correct. The
 * disallowed tool's executor still never runs. Tests below pin both halves
 * of that contract.
 */
class ToolAuthorizationTest {

    @Test
    fun `model emitting an unlisted tool gets a clear error in context — executor never runs, loop continues`() {
        var dangerousExecuted = false
        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.ToolCalls(listOf(ToolCall(name = "dangerousTool", arguments = emptyMap()))))
        // Second turn (after the recovery message): plain text answer ends the loop.
        responses.add(LlmResponse.Text("done"))
        var turnIndex = 0
        var seenOnTurn2: List<LlmMessage>? = null
        val mock = ModelClient { messages ->
            turnIndex++
            if (turnIndex == 2) seenOnTurn2 = messages.toList()
            responses.removeFirst()
        }

        val a = agent<String, String>("guarded") {
            lateinit var safeTool: Tool<Map<String, Any?>, Any?>
            model { ollama("llama3"); client = mock }
            tools {
                safeTool = tool("safeTool", "ok") { _ -> "safe-result" }
                tool("dangerousTool", "danger") { _ -> dangerousExecuted = true; "should-not-run" }
            }
            skills { skill<String, String>("only-safe", "stub") { tools(safeTool) } }
        }

        val out = a("input")

        assertFalse(dangerousExecuted, "the unlisted tool's executor must never run")
        assertEquals("done", out, "the loop must continue and complete via the model's next turn")

        // The recovery message must reach the model on the next turn.
        val msgs = seenOnTurn2 ?: fail("the model must get a second turn after recovery")
        val toolErr = msgs.firstOrNull { it.role == "tool" && it.content.contains("dangerousTool") }
            ?: fail("a tool message must name the offending tool 'dangerousTool'; got: ${msgs.filter { it.role == "tool" }.map { it.content }}")
        assertTrue(
            toolErr.content.contains("only-safe", ignoreCase = true) ||
                toolErr.content.contains("unknown", ignoreCase = true) ||
                toolErr.content.contains("allowed", ignoreCase = true),
            "the error must guide the model toward the allowed tool set: ${toolErr.content}",
        )
    }

    @Test
    fun `model emitting an entirely unknown name is recoverable too — error appended, loop continues`() {
        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.ToolCalls(listOf(ToolCall(name = "doesNotExistAnywhere", arguments = emptyMap()))))
        responses.add(LlmResponse.Text("done"))
        var seenOnTurn2: List<LlmMessage>? = null
        var turnIndex = 0
        val mock = ModelClient { messages ->
            turnIndex++
            if (turnIndex == 2) seenOnTurn2 = messages.toList()
            responses.removeFirst()
        }

        val a = agent<String, String>("a") {
            lateinit var safeTool: Tool<Map<String, Any?>, Any?>
            model { ollama("llama3"); client = mock }
            tools { safeTool = tool("safeTool", "ok") { _ -> "ok" } }
            skills { skill<String, String>("s", "stub") { tools(safeTool) } }
        }

        val out = a("input")

        assertEquals("done", out)
        val msgs = seenOnTurn2 ?: fail("the model must get a second turn after recovery")
        assertTrue(
            msgs.any { it.role == "tool" && it.content.contains("doesNotExistAnywhere") },
            "the recovery message must name the unknown tool",
        )
    }

    @Test
    fun `recovery message must NOT leak tools outside the skill's allowlist`() {
        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.ToolCalls(listOf(ToolCall(name = "secretTool", arguments = emptyMap()))))
        responses.add(LlmResponse.Text("done"))
        var seenOnTurn2: List<LlmMessage>? = null
        var turnIndex = 0
        val mock = ModelClient { messages ->
            turnIndex++
            if (turnIndex == 2) seenOnTurn2 = messages.toList()
            responses.removeFirst()
        }

        val a = agent<String, String>("a") {
            lateinit var publicTool: Tool<Map<String, Any?>, Any?>
            model { ollama("llama3"); client = mock }
            tools {
                publicTool = tool("publicTool", "") { _ -> "ok" }
                tool("secretTool", "") { _ -> "should-stay-hidden" }
                tool("anotherSecretTool", "") { _ -> "also-hidden" }
            }
            skills { skill<String, String>("s", "stub") { tools(publicTool) } }
        }

        a("input")

        val msgs = seenOnTurn2 ?: fail("expected a second turn carrying the recovery message")
        val recovery = msgs.firstOrNull { it.role == "tool" && it.content.contains("secretTool") }
            ?: fail("expected a recovery message for the secretTool call")
        assertFalse(
            recovery.content.contains("anotherSecretTool"),
            "recovery must not enumerate tools outside the skill's allowlist: ${recovery.content}",
        )
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
    fun `cross-skill call is refused — disjoint allowlists, executor never runs, loop recovers`() {
        var skillBToolExecuted = false
        val responses = ArrayDeque<LlmResponse>()
        // Model running under skill-A tries to call a tool that only skill-B declares
        responses.add(LlmResponse.ToolCalls(listOf(ToolCall(name = "bOnly", arguments = emptyMap()))))
        responses.add(LlmResponse.Text("done"))
        var seenOnTurn2: List<LlmMessage>? = null
        var turnIndex = 0
        val mock = ModelClient { messages ->
            turnIndex++
            if (turnIndex == 2) seenOnTurn2 = messages.toList()
            responses.removeFirst()
        }

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
            skillSelection { _ -> "skill-A" }
        }

        val out = a("input")

        assertFalse(skillBToolExecuted, "skill-A must not be able to call skill-B's tools")
        assertEquals("done", out)
        val msgs = seenOnTurn2 ?: fail("recovery must reach the model on a second turn")
        assertTrue(
            msgs.any { it.role == "tool" && it.content.contains("bOnly") },
            "recovery message must name the disallowed cross-skill tool",
        )
    }
}
