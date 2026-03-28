package agents_engine.model

import agents_engine.core.agent
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.assertThrows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests for tool error recovery with a live LLM.
 * Run with: ./gradlew integrationTest
 */
class ToolErrorIntegrationTest {

    @Tag("live-llm")
    @Test
    fun `flaky tool recovers via retry and LLM completes the task`() {
        fun num(args: Map<String, Any?>, key: String) = args[key].toString().toDouble()

        var addCallCount = 0
        val toolUses = mutableListOf<String>()

        val a = agent<String, String>("calculator") {
            prompt("You are a calculator. Use the provided tools to evaluate expressions step by step. Reply with ONLY the final number.")
            model { ollama("gpt-oss:120b-cloud"); host = "localhost"; port = 11434; temperature = 0.0 }
            tools {
                tool("add", "Add two numbers. Args: a, b") { args ->
                    addCallCount++
                    // Fail on first call, succeed on retry
                    if (addCallCount == 1) throw RuntimeException("Transient network error")
                    num(args, "a") + num(args, "b")
                }
                tool("multiply", "Multiply two numbers. Args: a, b") { args ->
                    num(args, "a") * num(args, "b")
                }
            }
            onToolError("add") {
                executionError { _ -> retry(maxAttempts = 3) }
            }
            skills { skill<String, String>("solve", "Evaluate arithmetic expressions using tools") {
                tools("add", "multiply")
            }}
            onToolUse { name, args, result ->
                toolUses.add(name)
                println("  $name(${args.values.joinToString(", ")}) = $result")
            }
        }

        // (3 + 7) * 5 = 50
        val result = a("Calculate (3 + 7) * 5")
        println("Result: $result")

        assertTrue(result.contains("50"), "Expected 50 in result, got: $result")
        assertEquals(2, addCallCount, "add should have been called twice (1 fail + 1 retry)")
        assertTrue(toolUses.contains("add"), "add tool should appear in successful tool uses")
        assertTrue(toolUses.contains("multiply"), "multiply tool should appear in tool uses")
    }

    @Tag("live-llm")
    @Test
    fun `retry exhaustion throws ToolExecutionException during live agentic loop`() {
        val a = agent<String, String>("calculator") {
            prompt("You are a calculator. Use the add tool. Reply with ONLY the final number.")
            model { ollama("gpt-oss:120b-cloud"); host = "localhost"; port = 11434; temperature = 0.0 }
            tools {
                tool("add", "Add two numbers. Args: a, b") { _ ->
                    throw RuntimeException("Service permanently down")
                }
            }
            onToolError("add") {
                executionError { _ -> retry(maxAttempts = 2) }
            }
            skills { skill<String, String>("solve", "Evaluate arithmetic expressions using tools") {
                tools("add")
            }}
        }

        val ex = assertThrows<ToolExecutionException> { a("Calculate 1 + 1") }
        println("Caught: ${ex.message}")
        assertTrue(ex.message!!.contains("add"), "Exception should mention the tool name")
        assertTrue(ex.message!!.contains("retries"), "Exception should mention retries")
    }

    @Tag("live-llm")
    @Test
    fun `escalation feeds error back to LLM which responds gracefully`() {
        val fixer = agent<String, String>("fixer") {
            skills {
                skill<String, String>("fix", "Attempt to fix tool errors") {
                    implementedBy { _ ->
                        throw EscalationException("Persistent hardware failure, cannot recover", Severity.CRITICAL)
                    }
                }
            }
        }

        val a = agent<String, String>("calculator") {
            prompt("You are a calculator. Use the add tool. If a tool returns an error, reply with the error description — do NOT retry the tool.")
            model { ollama("gpt-oss:120b-cloud"); host = "localhost"; port = 11434; temperature = 0.0 }
            budget { maxTurns = 5 }
            tools {
                tool("add", "Add two numbers. Args: a, b") { _ ->
                    throw RuntimeException("Hardware fault")
                }
            }
            onToolError("add") {
                executionError { _ -> fix(agent = fixer, retries = 1) }
            }
            skills { skill<String, String>("solve", "Evaluate arithmetic expressions using tools") {
                tools("add")
            }}
        }

        val result = a("Calculate 1 + 1")
        println("Result: $result")
        // LLM should see the error and respond (not crash)
        assertTrue(result.isNotBlank(), "LLM should produce a response after escalation")
    }

    @Tag("live-llm")
    @Test
    fun `agent-based repair fixes tool error and LLM continues`() {
        fun num(args: Map<String, Any?>, key: String) = args[key].toString().toDouble()

        var addCallCount = 0
        val toolUses = mutableListOf<Pair<String, Any?>>()

        // Repair agent that returns a fixed value when the tool fails
        val repairAgent = agent<String, String>("add-repairer") {
            skills {
                skill<String, String>("repair", "Returns a fixed fallback result") {
                    implementedBy { _ -> "10.0" }
                }
            }
        }

        val a = agent<String, String>("calculator") {
            prompt(
                "You are a calculator. You MUST use the provided tools for every operation — never compute mentally. " +
                "After all tool calls, reply with ONLY the final number — no explanation, no units."
            )
            model { ollama("gpt-oss:120b-cloud"); host = "localhost"; port = 11434; temperature = 0.0 }
            tools {
                tool("add", "Add two numbers. Args: a, b") { _ ->
                    addCallCount++
                    throw RuntimeException("add service unavailable")
                }
                tool("multiply", "Multiply two numbers. Args: a, b") { args ->
                    num(args, "a") * num(args, "b")
                }
            }
            onToolError("add") {
                executionError { _ -> fix(agent = repairAgent, retries = 1) }
            }
            skills { skill<String, String>("solve", "Evaluate arithmetic expressions using tools") {
                tools("add", "multiply")
            }}
            onToolUse { name, _, result ->
                toolUses.add(name to result)
                println("  $name -> $result")
            }
        }

        // (3 + 7) * 5 — add always fails but repair agent returns "10.0", so multiply gets 10 * 5 = 50
        val result = a("Calculate (3 + 7) * 5")
        println("Result: '$result'")

        // The add tool was repaired with "10.0", multiply should produce 50.0
        assertTrue(toolUses.any { it.first == "add" }, "add should appear in tool uses (with repaired result)")
        assertTrue(toolUses.any { it.first == "multiply" }, "multiply should have been called after repair")

        // multiply should have received 10.0 * 5 = 50.0
        val multiplyResult = toolUses.first { it.first == "multiply" }.second
        assertEquals(50.0, multiplyResult, "multiply should have computed 10 * 5 = 50")
    }

    @Tag("live-llm")
    @Test
    fun `defaults onError retry works across multiple tools with live LLM`() {
        fun num(args: Map<String, Any?>, key: String) = args[key].toString().toDouble()

        var addFailed = false
        var multiplyFailed = false

        val a = agent<String, String>("calculator") {
            prompt("You are a calculator. Use the provided tools. Reply with ONLY the final number.")
            model { ollama("gpt-oss:120b-cloud"); host = "localhost"; port = 11434; temperature = 0.0 }
            tools {
                defaults {
                    onError {
                        executionError { _ -> retry(maxAttempts = 3) }
                    }
                }
                tool("add", "Add two numbers. Args: a, b") { args ->
                    if (!addFailed) { addFailed = true; throw RuntimeException("Transient") }
                    num(args, "a") + num(args, "b")
                }
                tool("multiply", "Multiply two numbers. Args: a, b") { args ->
                    if (!multiplyFailed) { multiplyFailed = true; throw RuntimeException("Transient") }
                    num(args, "a") * num(args, "b")
                }
            }
            skills { skill<String, String>("solve", "Evaluate arithmetic expressions using tools") {
                tools("add", "multiply")
            }}
            onToolUse { name, args, result ->
                println("  $name(${args.values.joinToString(", ")}) = $result")
            }
        }

        // (2 + 3) * 4 = 20 — both tools fail once, then succeed on retry
        val result = a("Calculate (2 + 3) * 4")
        println("Result: $result")

        assertTrue(result.contains("20"), "Expected 20 in result, got: $result")
        assertTrue(addFailed, "add should have failed once")
        assertTrue(multiplyFailed, "multiply should have failed once")
    }
}
