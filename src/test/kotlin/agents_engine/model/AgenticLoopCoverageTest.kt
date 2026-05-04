package agents_engine.model

import agents_engine.core.agent
import agents_engine.generation.Generable
import agents_engine.generation.Guide
import org.junit.jupiter.api.assertThrows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Tests for #887 — coverage of AgenticLoop branches that mutation-killer tests
// in #841 didn't reach: Retry path in recoverInvalidArguments,
// Unrecoverable / null arms in executeToolWithExecutionRecovery, typed
// parseOutput branch.

@Generable("typed agent output")
data class TypedOutput(@Guide("a value") val v: String)

class AgenticLoopCoverageTest {

    // recoverInvalidArguments — Retry path (lines 317-336)

    @Test
    fun `invalidArgs Retry path executes tool when re-parse succeeds`() {
        // The tool call has invalidArgumentsError set BUT rawArguments is
        // actually valid JSON of the right shape. Handler returns Retry(2);
        // the inner repeat() block re-parses currentRaw (which IS valid),
        // typed validation passes, executor runs.
        val responses = ArrayDeque<LlmResponse>()
        responses.add(
            LlmResponse.ToolCalls(
                listOf(
                    ToolCall(
                        name = "double",
                        rawArguments = """{"value": 21}""",
                        invalidArgumentsError = "spurious — rawArguments is actually valid",
                    ),
                ),
            ),
        )
        responses.add(LlmResponse.Text("done"))
        val mock = ModelClient { _ -> responses.removeFirst() }

        var executorCalls = 0
        val a = agent<String, String>("a") {
            lateinit var double: Tool<Map<String, Any?>, Any?>
            model { ollama("test"); client = mock }
            tools {
                double = tool("double", "doubles") { args ->
                    executorCalls++
                    ((args["value"] as Number).toInt() * 2).toString()
                }
            }
            onToolError("double") {
                invalidArgs { _, _ -> retry(maxAttempts = 2) }
            }
            skills { skill<String, String>("s", "s") { tools(double) } }
        }

        assertEquals("done", a("input"))
        assertEquals(1, executorCalls, "executor should run exactly once via the Retry re-parse")
    }

    @Test
    fun `invalidArgs Retry exhausts attempts and throws when re-parse keeps failing`() {
        // rawArguments is genuinely malformed — every retry's parseToolArguments
        // returns parseError != null. After maxAttempts retries the loop
        // completes without ever returning, so the post-loop throw fires.
        val responses = ArrayDeque<LlmResponse>()
        responses.add(
            LlmResponse.ToolCalls(
                listOf(
                    ToolCall(
                        name = "double",
                        rawArguments = "definitely not json",
                        invalidArgumentsError = "Could not parse",
                    ),
                ),
            ),
        )
        val mock = ModelClient { _ -> responses.removeFirst() }

        val a = agent<String, String>("a") {
            lateinit var double: Tool<Map<String, Any?>, Any?>
            model { ollama("test"); client = mock }
            tools { double = tool("double", "") { _ -> "x" } }
            onToolError("double") {
                invalidArgs { _, _ -> retry(maxAttempts = 3) }
            }
            skills { skill<String, String>("s", "s") { tools(double) } }
        }

        val ex = assertThrows<ToolExecutionException> { a("input") }
        assertTrue(
            ex.message!!.contains("remained invalid"),
            "expected post-Retry-loop throw message: ${ex.message}",
        )
        assertTrue(ex.message!!.contains("3 retries"), "must mention attempt count: ${ex.message}")
    }

    // executeToolWithExecutionRecovery — Unrecoverable + null arms (lines 388, 391)

    @Test
    fun `executionError Unrecoverable wraps the original exception`() {
        // L388 — RepairResult.Unrecoverable arm.
        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.ToolCalls(listOf(ToolCall("boom", emptyMap()))))
        val mock = ModelClient { _ -> responses.removeFirst() }

        val a = agent<String, String>("a") {
            lateinit var boom: Tool<Map<String, Any?>, Any?>
            model { ollama("test"); client = mock }
            tools { boom = tool("boom", "") { _ -> throw RuntimeException("real-failure") } }
            onToolError("boom") {
                executionError { _ -> RepairResult.Unrecoverable }
            }
            skills { skill<String, String>("s", "s") { tools(boom) } }
        }

        val ex = assertThrows<ToolExecutionException> { a("input") }
        assertTrue(
            ex.message!!.contains("unrecoverable"),
            "expected 'unrecoverable' in message: ${ex.message}",
        )
        // The original exception should be the cause.
        assertTrue(
            ex.cause is RuntimeException && ex.cause!!.message == "real-failure",
            "original exception should be preserved as cause: ${ex.cause}",
        )
    }

    @Test
    fun `executionError handler returning null re-throws the original exception`() {
        // L391 — null arm. RepairScope.block returns null when not handled,
        // which surfaces as `null` in the when() — re-throws e.
        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.ToolCalls(listOf(ToolCall("boom", emptyMap()))))
        val mock = ModelClient { _ -> responses.removeFirst() }

        val a = agent<String, String>("a") {
            lateinit var boom: Tool<Map<String, Any?>, Any?>
            model { ollama("test"); client = mock }
            tools { boom = tool("boom", "") { _ -> throw RuntimeException("original-failure") } }
            onToolError("boom") {
                // Handler scope returns null → null → executionError returns null
                executionError { _ -> null }
            }
            skills { skill<String, String>("s", "s") { tools(boom) } }
        }

        // The original RuntimeException should propagate — NOT wrapped as
        // ToolExecutionException.
        val ex = assertThrows<RuntimeException> { a("input") }
        assertTrue(
            ex.message == "original-failure",
            "expected original exception passthrough; got: ${ex.message}",
        )
    }

    // parseOutput — typed (non-String) OUT branch (line 417)

    @Test
    fun `parseOutput uses fromLlmOutput for typed non-String output`() {
        // Agent has OUT = TypedOutput. Model returns JSON; framework parses
        // via fromLlmOutput, casts to TypedOutput.
        val mock = ModelClient { _ -> LlmResponse.Text("""{"v": "hello"}""") }

        val a = agent<String, TypedOutput>("a") {
            model { ollama("test"); client = mock }
            skills { skill<String, TypedOutput>("s", "s") { tools() } }
        }

        val result: TypedOutput = a("input")
        assertEquals("hello", result.v)
    }
}
