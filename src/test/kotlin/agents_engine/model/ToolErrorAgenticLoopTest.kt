package agents_engine.model

import agents_engine.core.agent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ToolErrorAgenticLoopTest {

    @Test
    fun `executionError retry recovers tool that fails then succeeds`() {
        var callCount = 0
        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.ToolCalls(listOf(ToolCall("flaky", mapOf("x" to "1")))))
        responses.add(LlmResponse.Text("done"))
        val mock = ModelClient { _ -> responses.removeFirst() }

        val a = agent<String, String>("a") {
            model { ollama("test"); client = mock }
            tools {
                tool("flaky", "A flaky tool") { _ ->
                    callCount++
                    if (callCount == 1) throw RuntimeException("Network error")
                    "success"
                }
            }
            onToolError("flaky") {
                executionError { _ -> retry(maxAttempts = 3) }
            }
            skills { skill<String, String>("s", "s") { tools("flaky") } }
        }

        val result = a("input")
        assertEquals("done", result)
        assertEquals(2, callCount) // failed once, succeeded on retry
    }

    @Test
    fun `executionError with no handler propagates exception`() {
        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.ToolCalls(listOf(ToolCall("broken", emptyMap()))))
        val mock = ModelClient { _ -> responses.removeFirst() }

        val a = agent<String, String>("a") {
            model { ollama("test"); client = mock }
            tools {
                tool("broken", "Always fails") { _ -> throw RuntimeException("Boom") }
            }
            skills { skill<String, String>("s", "s") { tools("broken") } }
        }

        var caught = false
        try {
            a("input")
        } catch (e: RuntimeException) {
            caught = true
            assertEquals("Boom", e.message)
        }
        assertTrue(caught, "Exception should propagate when no handler")
    }

    @Test
    fun `executionError retry exhaustion propagates as ToolExecutionException`() {
        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.ToolCalls(listOf(ToolCall("always-fail", emptyMap()))))
        val mock = ModelClient { _ -> responses.removeFirst() }

        val a = agent<String, String>("a") {
            model { ollama("test"); client = mock }
            tools {
                tool("always-fail", "Never works") { _ -> throw RuntimeException("Fail") }
            }
            onToolError("always-fail") {
                executionError { _ -> retry(maxAttempts = 2) }
            }
            skills { skill<String, String>("s", "s") { tools("always-fail") } }
        }

        var caught = false
        try {
            a("input")
        } catch (e: ToolExecutionException) {
            caught = true
            assertTrue(e.message!!.contains("always-fail"))
        }
        assertTrue(caught)
    }

    @Test
    fun `onToolUse fires after successful recovery`() {
        var callCount = 0
        val toolEvents = mutableListOf<String>()
        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.ToolCalls(listOf(ToolCall("flaky", emptyMap()))))
        responses.add(LlmResponse.Text("done"))
        val mock = ModelClient { _ -> responses.removeFirst() }

        val a = agent<String, String>("a") {
            model { ollama("test"); client = mock }
            tools {
                tool("flaky", "Flaky tool") { _ ->
                    callCount++
                    if (callCount == 1) throw RuntimeException("Fail")
                    "recovered"
                }
            }
            onToolError("flaky") {
                executionError { _ -> retry(maxAttempts = 2) }
            }
            skills { skill<String, String>("s", "s") { tools("flaky") } }
            onToolUse { name, _, result -> toolEvents.add("$name=$result") }
        }

        a("input")
        assertEquals(listOf("flaky=recovered"), toolEvents)
    }

    @Test
    fun `defaults onError works in agentic loop`() {
        var callCount = 0
        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.ToolCalls(listOf(ToolCall("retry-me", emptyMap()))))
        responses.add(LlmResponse.Text("done"))
        val mock = ModelClient { _ -> responses.removeFirst() }

        val a = agent<String, String>("a") {
            model { ollama("test"); client = mock }
            tools {
                defaults {
                    onError {
                        executionError { _ -> retry(maxAttempts = 3) }
                    }
                }
                tool("retry-me", "Needs retry") { _ ->
                    callCount++
                    if (callCount == 1) throw RuntimeException("Transient")
                    "ok"
                }
            }
            skills { skill<String, String>("s", "s") { tools("retry-me") } }
        }

        assertEquals("done", a("input"))
        assertEquals(2, callCount)
    }

    @Test
    fun `escalation in agentic loop feeds error back to LLM`() {
        val fixer = agent<String, String>("esc-fixer") {
            skills {
                skill<String, String>("fix", "Fix") {
                    implementedBy { _ -> throw EscalationException("Cannot fix schema", Severity.CRITICAL) }
                }
            }
        }

        val captured = mutableListOf<List<LlmMessage>>()
        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.ToolCalls(listOf(ToolCall("strict", emptyMap()))))
        responses.add(LlmResponse.Text("handled"))
        val mock = ModelClient { msgs -> captured.add(msgs.toList()); responses.removeFirst() }

        val a = agent<String, String>("a") {
            model { ollama("test"); client = mock }
            tools {
                tool("strict", "Strict tool") { _ -> throw RuntimeException("Bad args") }
            }
            onToolError("strict") {
                executionError { _ -> fix(agent = fixer, retries = 1) }
            }
            skills { skill<String, String>("s", "s") { tools("strict") } }
        }

        val result = a("input")
        assertEquals("handled", result)

        // LLM should see the escalation error in the tool message
        val secondCall = captured[1]
        val toolMsg = secondCall.last { it.role == "tool" }
        assertTrue(toolMsg.content.contains("Cannot fix schema"), "Error should be fed back: ${toolMsg.content}")
        assertTrue(toolMsg.content.contains("CRITICAL"), "Severity should be in message: ${toolMsg.content}")
    }
}
