package agents_engine.model

import agents_engine.core.agent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ToolLevelOnErrorTest {

    // -- onError inside tool definition --

    @Test
    fun `tool-level onError is accessible via getToolErrorHandler`() {
        val a = agent<String, String>("a") {
            tools {
                tool("fetch", "Fetch URL", onError = {
                    executionError { _ -> retry(maxAttempts = 3) }
                }) { _ -> "ok" }
            }
            skills { skill<String, String>("s", "s") { implementedBy { it } } }
        }

        assertNotNull(a.getToolErrorHandler("fetch"))
    }

    @Test
    fun `tool-level onError handles execution errors`() {
        val a = agent<String, String>("a") {
            tools {
                tool("fetch", "Fetch URL", onError = {
                    executionError { _ -> retry(maxAttempts = 5) }
                }) { _ -> "ok" }
            }
            skills { skill<String, String>("s", "s") { implementedBy { it } } }
        }

        val handler = a.getToolErrorHandler("fetch")!!
        val result = handler.handleExecutionError(RuntimeException("timeout"))
        assertIs<RepairResult.Retry>(result)
        assertEquals(5, result.maxAttempts)
    }

    @Test
    fun `tool-level onError handles invalidArgs via agent`() {
        val fixer = agent<String, String>("comma-fixer") {
            skills { skill<String, String>("fix", "Fix commas") {
                implementedBy { input -> input.replace(",}", "}") }
            }}
        }

        val a = agent<String, String>("a") {
            tools {
                tool("parse", "Parse JSON", onError = {
                    invalidArgs { _, _ -> fix(agent = fixer) }
                }) { _ -> "ok" }
            }
            skills { skill<String, String>("s", "s") { implementedBy { it } } }
        }

        val handler = a.getToolErrorHandler("parse")!!
        val result = handler.handleInvalidArgs("""{"a":1,}""", "trailing comma")
        assertIs<RepairResult.Fixed>(result)
        assertEquals("""{"a":1}""", result.value)
    }

    // -- Priority: tool-level > agent-level > defaults --

    @Test
    fun `tool-level onError takes priority over agent-level onToolError`() {
        val a = agent<String, String>("a") {
            tools {
                tool("fetch", "Fetch", onError = {
                    executionError { _ -> retry(maxAttempts = 10) }
                }) { _ -> "ok" }
            }
            // Agent-level handler for same tool — should be overridden
            onToolError("fetch") {
                executionError { _ -> retry(maxAttempts = 1) }
            }
            skills { skill<String, String>("s", "s") { implementedBy { it } } }
        }

        val handler = a.getToolErrorHandler("fetch")!!
        val result = handler.handleExecutionError(RuntimeException("fail"))
        assertIs<RepairResult.Retry>(result)
        assertEquals(10, result.maxAttempts, "tool-level handler should win over agent-level")
    }

    @Test
    fun `tool-level onError takes priority over defaults`() {
        val a = agent<String, String>("a") {
            tools {
                defaults {
                    onError {
                        executionError { _ -> retry(maxAttempts = 99) }
                    }
                }
                tool("fetch", "Fetch", onError = {
                    executionError { _ -> retry(maxAttempts = 2) }
                }) { _ -> "ok" }
            }
            skills { skill<String, String>("s", "s") { implementedBy { it } } }
        }

        val handler = a.getToolErrorHandler("fetch")!!
        val result = handler.handleExecutionError(RuntimeException("fail"))
        assertIs<RepairResult.Retry>(result)
        assertEquals(2, result.maxAttempts, "tool-level should win over defaults")
    }

    @Test
    fun `tool without onError falls back to agent-level`() {
        val a = agent<String, String>("a") {
            tools {
                tool("fetch", "Fetch") { _ -> "ok" }
            }
            onToolError("fetch") {
                executionError { _ -> retry(maxAttempts = 7) }
            }
            skills { skill<String, String>("s", "s") { implementedBy { it } } }
        }

        val handler = a.getToolErrorHandler("fetch")!!
        val result = handler.handleExecutionError(RuntimeException("fail"))
        assertIs<RepairResult.Retry>(result)
        assertEquals(7, result.maxAttempts)
    }

    @Test
    fun `tool without onError falls back to defaults when no agent-level`() {
        val a = agent<String, String>("a") {
            tools {
                defaults {
                    onError {
                        executionError { _ -> retry(maxAttempts = 4) }
                    }
                }
                tool("fetch", "Fetch") { _ -> "ok" }
            }
            skills { skill<String, String>("s", "s") { implementedBy { it } } }
        }

        val handler = a.getToolErrorHandler("fetch")!!
        val result = handler.handleExecutionError(RuntimeException("fail"))
        assertIs<RepairResult.Retry>(result)
        assertEquals(4, result.maxAttempts)
    }

    @Test
    fun `tool without any onError returns null handler`() {
        val a = agent<String, String>("a") {
            tools {
                tool("fetch", "Fetch") { _ -> "ok" }
            }
            skills { skill<String, String>("s", "s") { implementedBy { it } } }
        }

        assertNull(a.getToolErrorHandler("fetch"))
    }

    // -- Agentic loop integration --

    @Test
    fun `tool-level onError retry works in agentic loop`() {
        var callCount = 0
        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.ToolCalls(listOf(ToolCall("flaky", emptyMap()))))
        responses.add(LlmResponse.Text("done"))
        val mock = ModelClient { _ -> responses.removeFirst() }

        val a = agent<String, String>("a") {
            model { ollama("test"); client = mock }
            tools {
                tool("flaky", "Flaky tool", onError = {
                    executionError { _ -> retry(maxAttempts = 3) }
                }) { _ ->
                    callCount++
                    if (callCount == 1) throw RuntimeException("transient")
                    "recovered"
                }
            }
            skills { skill<String, String>("s", "s") { tools("flaky") } }
        }

        assertEquals("done", a("input"))
        assertEquals(2, callCount)
    }

    @Test
    fun `tool-level agent repair works in agentic loop`() {
        val fixer = agent<String, String>("fixer") {
            skills {
                skill<String, String>("fix", "Fix") {
                    implementedBy { _ -> "repaired-value" }
                }
            }
        }

        val toolResults = mutableListOf<Any?>()
        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.ToolCalls(listOf(ToolCall("broken", emptyMap()))))
        responses.add(LlmResponse.Text("done"))
        val mock = ModelClient { _ -> responses.removeFirst() }

        val a = agent<String, String>("a") {
            model { ollama("test"); client = mock }
            tools {
                tool("broken", "Always breaks", onError = {
                    executionError { _ -> fix(agent = fixer, retries = 1) }
                }) { _ -> throw RuntimeException("broken") }
            }
            skills { skill<String, String>("s", "s") { tools("broken") } }
            onToolUse { _, _, result -> toolResults.add(result) }
        }

        assertEquals("done", a("input"))
        assertEquals(listOf<Any?>("repaired-value"), toolResults)
    }

    @Test
    fun `mixed tools - some with onError, some with defaults, some bare`() {
        var fetchCalls = 0
        var compileCalls = 0
        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.ToolCalls(listOf(
            ToolCall("fetch", emptyMap()),
            ToolCall("compile", emptyMap()),
            ToolCall("log", emptyMap()),
        )))
        responses.add(LlmResponse.Text("done"))
        val mock = ModelClient { _ -> responses.removeFirst() }

        val a = agent<String, String>("a") {
            model { ollama("test"); client = mock }
            tools {
                defaults {
                    onError {
                        executionError { _ -> retry(maxAttempts = 2) }
                    }
                }
                // Tool-level onError
                tool("fetch", "Fetch", onError = {
                    executionError { _ -> retry(maxAttempts = 5) }
                }) { _ ->
                    fetchCalls++
                    if (fetchCalls == 1) throw RuntimeException("timeout")
                    "fetched"
                }
                // No tool-level onError — uses defaults
                tool("compile", "Compile") { _ ->
                    compileCalls++
                    if (compileCalls == 1) throw RuntimeException("flaky compile")
                    "compiled"
                }
                // Bare tool — no error handling at all
                tool("log", "Log") { _ -> "logged" }
            }
            skills { skill<String, String>("s", "s") { tools("fetch", "compile", "log") } }
        }

        assertEquals("done", a("input"))
        assertEquals(2, fetchCalls, "fetch should have retried once")
        assertEquals(2, compileCalls, "compile should have retried once via defaults")
    }

    // -- Escalation and throw inside tool-level onError --

    @Test
    fun `tool-level escalation feeds error back to LLM in agentic loop`() {
        val escalatingFixer = agent<String, String>("esc-fixer") {
            skills { skill<String, String>("fix", "Attempts repair") {
                implementedBy { _ ->
                    throw EscalationException("Schema mismatch, not a transient error", Severity.HIGH)
                }
            }}
        }

        val captured = mutableListOf<List<LlmMessage>>()
        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.ToolCalls(listOf(ToolCall("strict", emptyMap()))))
        responses.add(LlmResponse.Text("handled"))
        val mock = ModelClient { msgs -> captured.add(msgs.toList()); responses.removeFirst() }

        val a = agent<String, String>("a") {
            model { ollama("test"); client = mock }
            tools {
                tool("strict", "Strict tool", onError = {
                    executionError { _ -> fix(agent = escalatingFixer, retries = 1) }
                }) { _ -> throw RuntimeException("Bad input") }
            }
            skills { skill<String, String>("s", "s") { tools("strict") } }
        }

        val result = a("input")
        assertEquals("handled", result)

        val toolMsg = captured[1].last { it.role == "tool" }
        assertTrue(toolMsg.content.contains("Schema mismatch"), "Error fed back: ${toolMsg.content}")
    }

    @Test
    fun `tool-level throwException propagates ToolExecutionException in agentic loop`() {
        val hardFailFixer = agent<String, String>("hard-fail") {
            skills { skill<String, String>("fix", "Attempts repair") {
                implementedBy { _ ->
                    throw ToolExecutionException("Fundamentally broken — cannot recover")
                }
            }}
        }

        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.ToolCalls(listOf(ToolCall("doomed", emptyMap()))))
        val mock = ModelClient { _ -> responses.removeFirst() }

        val a = agent<String, String>("a") {
            model { ollama("test"); client = mock }
            tools {
                tool("doomed", "Doomed tool", onError = {
                    executionError { _ -> fix(agent = hardFailFixer, retries = 2) }
                }) { _ -> throw RuntimeException("Broken") }
            }
            skills { skill<String, String>("s", "s") { tools("doomed") } }
        }

        var caught = false
        try {
            a("input")
        } catch (e: ToolExecutionException) {
            caught = true
            assertEquals("Fundamentally broken — cannot recover", e.message)
        }
        assertTrue(caught, "ToolExecutionException should propagate immediately")
    }

    @Test
    fun `tool-level retry exhaustion throws ToolExecutionException`() {
        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.ToolCalls(listOf(ToolCall("always-fail", emptyMap()))))
        val mock = ModelClient { _ -> responses.removeFirst() }

        val a = agent<String, String>("a") {
            model { ollama("test"); client = mock }
            tools {
                tool("always-fail", "Never works", onError = {
                    executionError { _ -> retry(maxAttempts = 2) }
                }) { _ -> throw RuntimeException("Permanent failure") }
            }
            skills { skill<String, String>("s", "s") { tools("always-fail") } }
        }

        var caught = false
        try {
            a("input")
        } catch (e: ToolExecutionException) {
            caught = true
            assertTrue(e.message!!.contains("always-fail"), "Should mention tool name")
            assertTrue(e.message!!.contains("retries"), "Should mention retries")
        }
        assertTrue(caught)
    }

    @Test
    fun `tool-level escalation with invalidArgs handler`() {
        val escalatingFixer = agent<String, String>("esc-fixer") {
            skills { skill<String, String>("fix", "Tries to fix args") {
                implementedBy { _ ->
                    throw EscalationException("Binary garbage, not JSON", Severity.CRITICAL)
                }
            }}
        }

        val handler = OnErrorBuilder().apply {
            invalidArgs { _, _ -> fix(agent = escalatingFixer) }
        }.build()

        val result = handler.handleInvalidArgs("\u0000\u0001\u0002", "Not valid UTF-8")
        assertIs<RepairResult.Escalated>(result)
        assertEquals("Binary garbage, not JSON", result.reason)
        assertEquals(Severity.CRITICAL, result.severity)
    }

    @Test
    fun `tool-level throwException with invalidArgs handler propagates`() {
        val hardFail = agent<String, String>("hard-fail") {
            skills { skill<String, String>("fix", "Tries to fix") {
                implementedBy { _ ->
                    throw ToolExecutionException("Input is not recoverable")
                }
            }}
        }

        val handler = OnErrorBuilder().apply {
            invalidArgs { _, _ -> fix(agent = hardFail) }
        }.build()

        var caught = false
        try {
            handler.handleInvalidArgs("garbage", "error")
        } catch (e: ToolExecutionException) {
            caught = true
            assertEquals("Input is not recoverable", e.message)
        }
        assertTrue(caught, "ToolExecutionException should propagate through handler")
    }
}
