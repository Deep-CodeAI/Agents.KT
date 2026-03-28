package agents_engine.model

import agents_engine.core.agent
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.assertThrows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests for the throwException built-in tool.
 * throwException is a hard failure — ToolExecutionException propagates immediately.
 *
 * Run with: ./gradlew integrationTest --tests "agents_engine.model.ThrowExceptionIntegrationTest"
 */
class ThrowExceptionIntegrationTest {

    // -- Unit test with mock LLM --

    @Test
    fun `throwException via tool in agentic loop kills the run`() {
        val fixerResponses = ArrayDeque<LlmResponse>()
        fixerResponses.add(LlmResponse.ToolCalls(listOf(
            ToolCall("throwException", mapOf("reason" to "Binary data detected — not recoverable"))
        )))
        val fixerMock = ModelClient { _ -> fixerResponses.removeFirst() }

        val fixer = agent<String, String>("binary-detector") {
            prompt("Analyze the input. If it's binary data, call throwException.")
            model { ollama("test"); client = fixerMock }
            skills { skill<String, String>("fix", "Detect binary") { tools("throwException") } }
        }

        val mainResponses = ArrayDeque<LlmResponse>()
        mainResponses.add(LlmResponse.ToolCalls(listOf(ToolCall("decode", mapOf("data" to "\u0000\u0001\u0002")))))
        val mainMock = ModelClient { _ -> mainResponses.removeFirst() }

        val a = agent<String, String>("decoder") {
            model { ollama("test"); client = mainMock }
            tools {
                tool("decode") {
                    description("Decode input data")
                    executor { _ -> throw RuntimeException("Cannot decode binary") }
                    onError {
                        executionError { _ -> fix(agent = fixer, retries = 1) }
                    }
                }
            }
            skills { skill<String, String>("s", "s") { tools("decode") } }
        }

        val ex = assertThrows<ToolExecutionException> { a("Decode this: \u0000\u0001\u0002") }
        assertEquals("Binary data detected — not recoverable", ex.message)
    }

    @Test
    fun `throwException propagates even when retries remain`() {
        val fixerResponses = ArrayDeque<LlmResponse>()
        fixerResponses.add(LlmResponse.ToolCalls(listOf(
            ToolCall("throwException", mapOf("reason" to "Impossible to fix"))
        )))
        val fixerMock = ModelClient { _ -> fixerResponses.removeFirst() }

        val fixer = agent<String, String>("fixer") {
            model { ollama("test"); client = fixerMock }
            skills { skill<String, String>("fix", "Fix") { tools("throwException") } }
        }

        val mainResponses = ArrayDeque<LlmResponse>()
        mainResponses.add(LlmResponse.ToolCalls(listOf(ToolCall("process", emptyMap()))))
        mainResponses.add(LlmResponse.Text("should never reach here"))
        val mainMock = ModelClient { _ -> mainResponses.removeFirst() }

        val a = agent<String, String>("a") {
            model { ollama("test"); client = mainMock }
            tools {
                tool("process") {
                    description("Process data")
                    executor { _ -> throw RuntimeException("Broken") }
                    onError {
                        executionError { _ -> fix(agent = fixer, retries = 5) }  // 5 retries available
                    }
                }
            }
            skills { skill<String, String>("s", "s") { tools("process") } }
        }

        // throwException kills the run on first attempt — doesn't use remaining retries
        val ex = assertThrows<ToolExecutionException> { a("input") }
        assertEquals("Impossible to fix", ex.message)
    }

    @Test
    fun `throwException does not fire onToolUse`() {
        val fixerResponses = ArrayDeque<LlmResponse>()
        fixerResponses.add(LlmResponse.ToolCalls(listOf(
            ToolCall("throwException", mapOf("reason" to "Dead"))
        )))
        val fixerMock = ModelClient { _ -> fixerResponses.removeFirst() }

        val fixer = agent<String, String>("fixer") {
            model { ollama("test"); client = fixerMock }
            skills { skill<String, String>("fix", "Fix") { tools("throwException") } }
        }

        val toolUses = mutableListOf<String>()
        val mainResponses = ArrayDeque<LlmResponse>()
        mainResponses.add(LlmResponse.ToolCalls(listOf(ToolCall("broken", emptyMap()))))
        val mainMock = ModelClient { _ -> mainResponses.removeFirst() }

        val a = agent<String, String>("a") {
            model { ollama("test"); client = mainMock }
            tools {
                tool("broken") {
                    description("Broken tool")
                    executor { _ -> throw RuntimeException("Fail") }
                    onError {
                        executionError { _ -> fix(agent = fixer, retries = 1) }
                    }
                }
            }
            skills { skill<String, String>("s", "s") { tools("broken") } }
            onToolUse { name, _, _ -> toolUses.add(name) }
        }

        assertThrows<ToolExecutionException> { a("input") }
        assertTrue(toolUses.isEmpty(), "onToolUse should not fire when throwException kills the run")
    }

    @Test
    fun `throwException from deterministic agent in tool block onError`() {
        val hardFail = agent<String, String>("hard-fail") {
            skills { skill<String, String>("fix", "Detect and reject") {
                implementedBy { _ ->
                    throw ToolExecutionException("Corrupt data — aborting pipeline")
                }
            }}
        }

        val mainResponses = ArrayDeque<LlmResponse>()
        mainResponses.add(LlmResponse.ToolCalls(listOf(ToolCall("ingest", emptyMap()))))
        val mainMock = ModelClient { _ -> mainResponses.removeFirst() }

        val a = agent<String, String>("a") {
            model { ollama("test"); client = mainMock }
            tools {
                tool("ingest") {
                    description("Ingest data")
                    executor { _ -> throw RuntimeException("Bad data") }
                    onError {
                        executionError { _ -> fix(agent = hardFail, retries = 3) }
                    }
                }
            }
            skills { skill<String, String>("s", "s") { tools("ingest") } }
        }

        val ex = assertThrows<ToolExecutionException> { a("input") }
        assertEquals("Corrupt data — aborting pipeline", ex.message)
    }

    // -- Live LLM integration test --
    // Fixer is deterministic (always throws ToolExecutionException).
    // Main agent uses a live LLM that calls the tool — pipeline stops immediately.

    @Tag("live-llm")
    @Test
    fun `live LLM - throwException from fixer stops pipeline`() {
        // Deterministic fixer — always hard-fails for binary data
        val fixer = agent<String, String>("binary-detector") {
            skills { skill<String, String>("detect", "Reject binary data") {
                implementedBy { _ ->
                    throw ToolExecutionException("Binary data detected — pipeline aborted")
                }
            }}
        }

        val toolUses = mutableListOf<String>()

        val a = agent<String, String>("decoder") {
            prompt(
                "You MUST use the decode tool to decode the input. " +
                "Reply with the decoded text. Args: data (string)."
            )
            model { ollama("gpt-oss:120b-cloud"); host = "localhost"; port = 11434; temperature = 0.0 }
            budget { maxTurns = 5 }
            tools {
                tool("decode") {
                    description("Decode text input. Args: data (string)")
                    executor { _ ->
                        throw RuntimeException("Invalid UTF-8 sequence: null bytes detected")
                    }
                    onError {
                        executionError { _ -> fix(agent = fixer, retries = 1) }
                    }
                }
            }
            skills { skill<String, String>("s", "Decode data") { tools("decode") } }
            onToolUse { name, _, _ -> toolUses.add(name) }
        }

        val ex = assertThrows<ToolExecutionException> {
            a("Decode this binary data: \\x00\\x01\\x02\\xFF\\xFE")
        }
        println("Caught: ${ex.message}")
        assertEquals("Binary data detected — pipeline aborted", ex.message)
        assertTrue(toolUses.isEmpty(), "onToolUse should not fire — throwException kills before result")
    }
}
