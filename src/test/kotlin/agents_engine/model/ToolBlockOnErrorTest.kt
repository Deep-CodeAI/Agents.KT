package agents_engine.model

import agents_engine.core.agent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ToolBlockOnErrorTest {

    // -- onError inside tool block DSL --

    @Test
    fun `tool block with onError registers handler`() {
        val fixer = agent<String, String>("fixer") {
            skills { skill<String, String>("fix", "Fix") {
                implementedBy { _ -> "fixed" }
            }}
        }

        val a = agent<String, String>("a") {
            tools {
                tool("parse") {
                    description("Parse JSON")
                    executor { _ -> "ok" }
                    onError {
                        invalidArgs { _, _ -> fix(agent = fixer) }
                    }
                }
            }
            skills { skill<String, String>("s", "s") { implementedBy { it } } }
        }

        assertNotNull(a.getToolErrorHandler("parse"))
    }

    @Test
    fun `tool block onError handles executionError with retry`() {
        val a = agent<String, String>("a") {
            tools {
                tool("fetch") {
                    description("Fetch URL")
                    executor { _ -> "data" }
                    onError {
                        executionError { _ -> retry(maxAttempts = 5) }
                    }
                }
            }
            skills { skill<String, String>("s", "s") { implementedBy { it } } }
        }

        val handler = a.getToolErrorHandler("fetch")!!
        val result = handler.handleExecutionError(RuntimeException("timeout"))
        assertIs<RepairResult.Retry>(result)
        assertEquals(5, result.maxAttempts)
    }

    @Test
    fun `tool block onError handles invalidArgs with agent fix`() {
        val fixer = agent<String, String>("json-fixer") {
            skills { skill<String, String>("fix", "Fix") {
                implementedBy { input -> input.replace(",}", "}") }
            }}
        }

        val a = agent<String, String>("a") {
            tools {
                tool("parse") {
                    description("Parse JSON")
                    executor { _ -> "ok" }
                    onError {
                        invalidArgs { _, _ -> fix(agent = fixer) }
                    }
                }
            }
            skills { skill<String, String>("s", "s") { implementedBy { it } } }
        }

        val handler = a.getToolErrorHandler("parse")!!
        val result = handler.handleInvalidArgs("""{"a":1,}""", "trailing comma")
        assertIs<RepairResult.Fixed>(result)
        assertEquals("""{"a":1}""", result.value)
    }

    @Test
    fun `tool block onError takes priority over defaults`() {
        val a = agent<String, String>("a") {
            tools {
                defaults {
                    onError {
                        executionError { _ -> retry(maxAttempts = 99) }
                    }
                }
                tool("fetch") {
                    description("Fetch URL")
                    executor { _ -> "data" }
                    onError {
                        executionError { _ -> retry(maxAttempts = 2) }
                    }
                }
            }
            skills { skill<String, String>("s", "s") { implementedBy { it } } }
        }

        val handler = a.getToolErrorHandler("fetch")!!
        val result = handler.handleExecutionError(RuntimeException("fail"))
        assertIs<RepairResult.Retry>(result)
        assertEquals(2, result.maxAttempts, "tool block onError should win over defaults")
    }

    @Test
    fun `tool block onError takes priority over agent-level onToolError`() {
        val a = agent<String, String>("a") {
            tools {
                tool("fetch") {
                    description("Fetch URL")
                    executor { _ -> "data" }
                    onError {
                        executionError { _ -> retry(maxAttempts = 10) }
                    }
                }
            }
            onToolError("fetch") {
                executionError { _ -> retry(maxAttempts = 1) }
            }
            skills { skill<String, String>("s", "s") { implementedBy { it } } }
        }

        val handler = a.getToolErrorHandler("fetch")!!
        val result = handler.handleExecutionError(RuntimeException("fail"))
        assertIs<RepairResult.Retry>(result)
        assertEquals(10, result.maxAttempts, "tool block onError should win over agent-level")
    }

    // -- Agentic loop integration --

    @Test
    fun `tool block onError retry works in agentic loop`() {
        var callCount = 0
        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.ToolCalls(listOf(ToolCall("flaky", emptyMap()))))
        responses.add(LlmResponse.Text("done"))
        val mock = ModelClient { _ -> responses.removeFirst() }

        val a = agent<String, String>("a") {
            model { ollama("test"); client = mock }
            tools {
                tool("flaky") {
                    description("Flaky tool")
                    executor { _ ->
                        callCount++
                        if (callCount == 1) throw RuntimeException("transient")
                        "recovered"
                    }
                    onError {
                        executionError { _ -> retry(maxAttempts = 3) }
                    }
                }
            }
            skills { skill<String, String>("s", "s") { tools("flaky") } }
        }

        assertEquals("done", a("input"))
        assertEquals(2, callCount)
    }

    @Test
    fun `tool block onError escalation feeds error back to LLM`() {
        val fixer = agent<String, String>("esc-fixer") {
            skills { skill<String, String>("fix", "Fix") {
                implementedBy { _ ->
                    throw EscalationException("Cannot recover — data is corrupted", Severity.HIGH)
                }
            }}
        }

        val captured = mutableListOf<List<LlmMessage>>()
        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.ToolCalls(listOf(ToolCall("parse", emptyMap()))))
        responses.add(LlmResponse.Text("handled"))
        val mock = ModelClient { msgs -> captured.add(msgs.toList()); responses.removeFirst() }

        val a = agent<String, String>("a") {
            model { ollama("test"); client = mock }
            tools {
                tool("parse") {
                    description("Parse data")
                    executor { _ -> throw RuntimeException("corrupt input") }
                    onError {
                        executionError { _ -> fix(agent = fixer, retries = 1) }
                    }
                }
            }
            skills { skill<String, String>("s", "s") { tools("parse") } }
        }

        val result = a("input")
        assertEquals("handled", result)

        val toolMsg = captured[1].last { it.role == "tool" }
        assertTrue(toolMsg.content.contains("Cannot recover"), "Escalation error fed back: ${toolMsg.content}")
    }

    @Test
    fun `tool block without onError falls back to defaults`() {
        var callCount = 0
        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.ToolCalls(listOf(ToolCall("plain", emptyMap()))))
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
                tool("plain") {
                    description("Plain tool")
                    executor { _ ->
                        callCount++
                        if (callCount == 1) throw RuntimeException("transient")
                        "ok"
                    }
                }
            }
            skills { skill<String, String>("s", "s") { tools("plain") } }
        }

        assertEquals("done", a("input"))
        assertEquals(2, callCount, "Should retry via defaults")
    }

    @Test
    fun `tool block description appears in system prompt`() {
        val captured = mutableListOf<List<LlmMessage>>()
        val mock = ModelClient { msgs -> captured.add(msgs.toList()); LlmResponse.Text("done") }

        val a = agent<String, String>("a") {
            model { ollama("test"); client = mock }
            tools {
                tool("analyze") {
                    description("Analyze data for patterns")
                    executor { _ -> "patterns found" }
                }
            }
            skills { skill<String, String>("s", "s") { tools("analyze") } }
        }

        a("input")

        val systemMsg = captured.single().first { it.role == "system" }
        assertTrue(systemMsg.content.contains("analyze"), "Tool name in prompt")
        assertTrue(systemMsg.content.contains("Analyze data for patterns"), "Description in prompt")
    }
}
