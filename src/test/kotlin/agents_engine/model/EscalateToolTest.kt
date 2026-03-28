package agents_engine.model

import agents_engine.core.agent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EscalateToolTest {

    // -- Built-in tools exist on every agent --

    @Test
    fun `every agent has escalate tool in toolMap`() {
        val a = agent<String, String>("a") {
            skills { skill<String, String>("s", "s") { implementedBy { it } } }
        }

        assertTrue(a.toolMap.containsKey("escalate"), "escalate should be built-in")
    }

    @Test
    fun `every agent has throwException tool in toolMap`() {
        val a = agent<String, String>("a") {
            skills { skill<String, String>("s", "s") { implementedBy { it } } }
        }

        assertTrue(a.toolMap.containsKey("throwException"), "throwException should be built-in")
    }

    @Test
    fun `built-in tools are not included in agentic loop unless skill references them`() {
        val captured = mutableListOf<List<LlmMessage>>()
        val mock = ModelClient { msgs -> captured.add(msgs.toList()); LlmResponse.Text("done") }

        val a = agent<String, String>("a") {
            model { ollama("test"); client = mock }
            tools {
                tool("greet", "Greet someone") { _ -> "Hi" }
            }
            skills { skill<String, String>("s", "s") { tools("greet") } }
        }

        a("input")

        val systemMsg = captured.single().first { it.role == "system" }
        assertTrue(systemMsg.content.contains("greet"), "greet should be in system prompt")
        assertTrue(!systemMsg.content.contains("escalate"), "escalate should NOT be in system prompt when not referenced")
        assertTrue(!systemMsg.content.contains("throwException"), "throwException should NOT be in system prompt when not referenced")
    }

    @Test
    fun `built-in tools appear in agentic loop when skill references them`() {
        val captured = mutableListOf<List<LlmMessage>>()
        val mock = ModelClient { msgs -> captured.add(msgs.toList()); LlmResponse.Text("done") }

        val a = agent<String, String>("a") {
            model { ollama("test"); client = mock }
            skills { skill<String, String>("s", "s") { tools("escalate", "throwException") } }
        }

        a("input")

        val systemMsg = captured.single().first { it.role == "system" }
        assertTrue(systemMsg.content.contains("escalate"), "escalate should be in system prompt")
        assertTrue(systemMsg.content.contains("throwException"), "throwException should be in system prompt")
    }

    // -- LLM-driven repair agent calls escalate tool --

    @Test
    fun `repair agent calling escalate tool produces EscalationException`() {
        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.ToolCalls(listOf(
            ToolCall("escalate", mapOf("reason" to "Cannot fix this schema", "severity" to "HIGH"))
        )))
        val mock = ModelClient { _ -> responses.removeFirst() }

        val fixer = agent<String, String>("json-fixer") {
            prompt("Fix malformed JSON. If you cannot fix it, call escalate.")
            model { ollama("test"); client = mock }
            skills { skill<String, String>("fix", "Fix JSON") { tools("escalate") } }
        }

        val handler = OnErrorBuilder().apply {
            invalidArgs { _, _ -> fix(agent = fixer) }
        }.build()

        val result = handler.handleInvalidArgs("total garbage", "Not JSON")
        assertIs<RepairResult.Escalated>(result)
        assertEquals("Cannot fix this schema", result.reason)
        assertEquals(Severity.HIGH, result.severity)
    }

    // -- LLM-driven repair agent calls throwException tool --

    @Test
    fun `repair agent calling throwException tool propagates ToolExecutionException`() {
        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.ToolCalls(listOf(
            ToolCall("throwException", mapOf("reason" to "Binary data, not JSON"))
        )))
        val mock = ModelClient { _ -> responses.removeFirst() }

        val fixer = agent<String, String>("json-fixer") {
            prompt("Fix malformed JSON. If fundamentally broken, call throwException.")
            model { ollama("test"); client = mock }
            skills { skill<String, String>("fix", "Fix JSON") { tools("throwException") } }
        }

        val handler = OnErrorBuilder().apply {
            invalidArgs { _, _ -> fix(agent = fixer) }
        }.build()

        var caught = false
        try {
            handler.handleInvalidArgs("binary garbage", "Not valid")
        } catch (e: ToolExecutionException) {
            caught = true
            assertEquals("Binary data, not JSON", e.message)
        }
        assertTrue(caught, "ToolExecutionException should propagate")
    }

    // -- Full flow: repair agent escalates via tool inside agentic loop --

    @Test
    fun `repair agent escalates via tool in outer agentic loop`() {
        val fixerResponses = ArrayDeque<LlmResponse>()
        fixerResponses.add(LlmResponse.ToolCalls(listOf(
            ToolCall("escalate", mapOf("reason" to "Schema mismatch — not formatting", "severity" to "CRITICAL"))
        )))
        val fixerMock = ModelClient { _ -> fixerResponses.removeFirst() }

        val fixer = agent<String, String>("smart-fixer") {
            prompt("Attempt to fix. If structural error, call escalate.")
            model { ollama("test"); client = fixerMock }
            skills { skill<String, String>("fix", "Fix JSON") { tools("escalate") } }
        }

        val captured = mutableListOf<List<LlmMessage>>()
        val loopResponses = ArrayDeque<LlmResponse>()
        loopResponses.add(LlmResponse.ToolCalls(listOf(ToolCall("parse", emptyMap()))))
        loopResponses.add(LlmResponse.Text("handled after escalation"))
        val loopMock = ModelClient { msgs -> captured.add(msgs.toList()); loopResponses.removeFirst() }

        val a = agent<String, String>("worker") {
            model { ollama("test"); client = loopMock }
            tools {
                tool("parse", "Parse data", onError = {
                    executionError { _ -> fix(agent = fixer, retries = 1) }
                }) { _ -> throw RuntimeException("Parse failed") }
            }
            skills { skill<String, String>("s", "s") { tools("parse") } }
        }

        val result = a("input")
        assertEquals("handled after escalation", result)

        // LLM should see the escalation error fed back as tool result
        val toolMsg = captured[1].last { it.role == "tool" }
        assertTrue(toolMsg.content.contains("Schema mismatch"), "Error fed back: ${toolMsg.content}")
    }

    // -- Severity parsing --

    @Test
    fun `escalate tool parses severity case-insensitively`() {
        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.ToolCalls(listOf(
            ToolCall("escalate", mapOf("reason" to "test", "severity" to "low"))
        )))
        val mock = ModelClient { _ -> responses.removeFirst() }

        val fixer = agent<String, String>("fixer") {
            model { ollama("test"); client = mock }
            skills { skill<String, String>("fix", "Fix") { tools("escalate") } }
        }

        val handler = OnErrorBuilder().apply {
            invalidArgs { _, _ -> fix(agent = fixer) }
        }.build()

        val result = handler.handleInvalidArgs("bad", "error")
        assertIs<RepairResult.Escalated>(result)
        assertEquals(Severity.LOW, result.severity)
    }

    @Test
    fun `escalate tool defaults severity to HIGH when not provided`() {
        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.ToolCalls(listOf(
            ToolCall("escalate", mapOf("reason" to "broken"))
        )))
        val mock = ModelClient { _ -> responses.removeFirst() }

        val fixer = agent<String, String>("fixer") {
            model { ollama("test"); client = mock }
            skills { skill<String, String>("fix", "Fix") { tools("escalate") } }
        }

        val handler = OnErrorBuilder().apply {
            invalidArgs { _, _ -> fix(agent = fixer) }
        }.build()

        val result = handler.handleInvalidArgs("bad", "error")
        assertIs<RepairResult.Escalated>(result)
        assertEquals(Severity.HIGH, result.severity)
    }

    // -- User-defined tools don't collide --

    @Test
    fun `user tools coexist with built-in escalate and throwException`() {
        val a = agent<String, String>("a") {
            tools {
                tool("greet", "Greet") { _ -> "Hi" }
                tool("farewell", "Farewell") { _ -> "Bye" }
            }
            skills { skill<String, String>("s", "s") { implementedBy { it } } }
        }

        assertTrue(a.toolMap.containsKey("greet"))
        assertTrue(a.toolMap.containsKey("farewell"))
        assertTrue(a.toolMap.containsKey("escalate"))
        assertTrue(a.toolMap.containsKey("throwException"))
        assertEquals(4, a.toolMap.size)
    }
}
