package agents_engine.model

import agents_engine.core.agent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Tests for #658 — typed validation must re-run on repaired args. Previously,
 * `RepairResult.Fixed` returning syntactically valid but typed-invalid JSON
 * went straight to the executor and the failure was misclassified as
 * `executionError` instead of `invalidArgs`.
 */
class RepairedArgsValidationTest {

    @Test
    fun `Fixed handler that produces typed-valid args reaches the executor`() {
        // Happy path regression: the Fixed value IS valid → executor runs with constructed args.
        var capturedName: String? = null
        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.ToolCalls(listOf(ToolCall(name = "greet", arguments = emptyMap()))))
        responses.add(LlmResponse.Text("done"))
        val mock = ModelClient { _ -> responses.removeFirst() }

        val a = agent<String, String>("a") {
            model { ollama("llama3"); client = mock }
            tools {
                tool<GreetArgs, GreetResult>("greet", "Greets") { args ->
                    capturedName = args.name
                    GreetResult("hi")
                }
            }
            skills { skill<String, String>("s", "stub") { tools("greet") } }
            onToolError("greet") {
                invalidArgs { _, _ -> RepairResult.Fixed("""{"name":"world"}""") }
            }
        }

        a("input")
        assertEquals("world", capturedName, "executor must receive the repaired typed args")
    }

    @Test
    fun `Fixed handler that produces still-invalid typed args re-routes through invalidArgs`() {
        var invalidArgsHandlerCallCount = 0
        var executionErrorHandlerCallCount = 0
        var executorCallCount = 0
        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.ToolCalls(listOf(ToolCall(name = "greet", arguments = emptyMap()))))
        responses.add(LlmResponse.Text("done"))
        val mock = ModelClient { _ -> responses.removeFirst() }

        val a = agent<String, String>("a") {
            model { ollama("llama3"); client = mock }
            tools {
                tool<GreetArgs, GreetResult>("greet", "Greets") { _ ->
                    executorCallCount++
                    GreetResult("never")
                }
            }
            skills { skill<String, String>("s", "stub") { tools("greet") } }
            onToolError("greet") {
                invalidArgs { _, _ ->
                    invalidArgsHandlerCallCount++
                    // Returns syntactically valid JSON but missing required `name` field
                    RepairResult.Fixed("""{"oops":"still wrong"}""")
                }
                executionError { _ ->
                    executionErrorHandlerCallCount++
                    null
                }
            }
        }

        try { a("input") } catch (_: Throwable) { /* expected */ }

        assertTrue(invalidArgsHandlerCallCount >= 2,
            "invalidArgs handler must be called for the initial bad args AND for the re-validated repair (got $invalidArgsHandlerCallCount)")
        assertEquals(0, executionErrorHandlerCallCount,
            "executionError handler must NOT fire — typed deserialization failure is an invalidArgs concern, not an execution failure")
        assertEquals(0, executorCallCount, "executor must never run with bad typed args")
    }

    @Test
    fun `repaired args that pass typed validation execute exactly once`() {
        var executorCalls = 0
        var invalidArgsCalls = 0
        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.ToolCalls(listOf(ToolCall(name = "greet", arguments = emptyMap()))))
        responses.add(LlmResponse.Text("done"))
        val mock = ModelClient { _ -> responses.removeFirst() }

        val a = agent<String, String>("a") {
            model { ollama("llama3"); client = mock }
            tools {
                tool<GreetArgs, GreetResult>("greet", "Greets") { args ->
                    executorCalls++
                    GreetResult("hi ${args.name}")
                }
            }
            skills { skill<String, String>("s", "stub") { tools("greet") } }
            onToolError("greet") {
                invalidArgs { _, _ ->
                    invalidArgsCalls++
                    RepairResult.Fixed("""{"name":"corrected"}""")
                }
            }
        }

        a("input")
        assertEquals(1, invalidArgsCalls, "handler fires once for the initial bad args")
        assertEquals(1, executorCalls, "executor runs once with the repaired (now-valid) args")
    }
}
