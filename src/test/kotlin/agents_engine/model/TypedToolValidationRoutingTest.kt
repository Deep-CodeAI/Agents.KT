package agents_engine.model

import agents_engine.core.agent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Tests for #636 — typed tool args validated before the executor runs;
 * deserialization failures route through the same `onError.invalidArgs`
 * handler that handles JSON-parse failures, not through `executionError`
 * (which is for executor-thrown exceptions during real work).
 */
class TypedToolValidationRoutingTest {

    @Test
    fun `missing required field routes through invalidArgs handler with type name in message`() {
        val capturedErrors = mutableListOf<String>()
        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.ToolCalls(listOf(ToolCall(name = "greet", arguments = emptyMap()))))
        responses.add(LlmResponse.Text("done"))
        val mock = ModelClient { _ -> responses.removeFirst() }

        var executorCalls = 0
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
                invalidArgs { _, error ->
                    capturedErrors.add(error)
                    null  // → Unrecoverable in the framework
                }
            }
        }

        try { a("input") } catch (_: Throwable) { /* expected */ }

        assertEquals(0, executorCalls, "executor must NOT be called when typed deserialization fails")
        assertTrue(capturedErrors.isNotEmpty(), "invalidArgs handler must have fired")
        val msg = capturedErrors.first()
        assertTrue(
            msg.contains("GreetArgs", ignoreCase = true) || msg.contains("name", ignoreCase = true),
            "error must mention the type or missing field: $msg",
        )
    }

    @Test
    fun `missing required field with no onError handler throws ToolExecutionException`() {
        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.ToolCalls(listOf(ToolCall(name = "greet", arguments = emptyMap()))))
        responses.add(LlmResponse.Text("done"))
        val mock = ModelClient { _ -> responses.removeFirst() }

        val a = agent<String, String>("a") {
            model { ollama("llama3"); client = mock }
            tools {
                tool<GreetArgs, GreetResult>("greet", "Greets") { args -> GreetResult(args.name) }
            }
            skills { skill<String, String>("s", "stub") { tools("greet") } }
        }

        try {
            a("input")
            fail("expected throw on bad typed args without handler")
        } catch (e: Throwable) {
            assertTrue(
                e.message!!.contains("GreetArgs", ignoreCase = true) ||
                    e.message!!.contains("name", ignoreCase = true) ||
                    e.message!!.contains("deserialize", ignoreCase = true),
                "error must mention type / field / deserialization: ${e.message}",
            )
        }
    }

    @Test
    fun `valid typed args invoke executor with constructed Args (regression of 634)`() {
        var captured: GreetArgs? = null
        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.ToolCalls(listOf(ToolCall(name = "greet", arguments = mapOf("name" to "ok", "language" to "en")))))
        responses.add(LlmResponse.Text("done"))
        val mock = ModelClient { _ -> responses.removeFirst() }

        val a = agent<String, String>("a") {
            model { ollama("llama3"); client = mock }
            tools {
                tool<GreetArgs, GreetResult>("greet", "Greets") { args ->
                    captured = args
                    GreetResult("hi")
                }
            }
            skills { skill<String, String>("s", "stub") { tools("greet") } }
        }
        a("input")
        assertEquals("ok", captured?.name)
    }

    @Test
    fun `untyped tool's onError invalidArgs path still works (regression)`() {
        var capturedErrors = 0
        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.ToolCalls(listOf(ToolCall(
            name = "echo",
            arguments = emptyMap(),
            rawArguments = "not-json",
            invalidArgumentsError = "Could not parse tool arguments as JSON object.",
        ))))
        responses.add(LlmResponse.Text("done"))
        val mock = ModelClient { _ -> responses.removeFirst() }

        val a = agent<String, String>("a") {
            model { ollama("llama3"); client = mock }
            tools { tool("echo", "echo untyped") { args -> "got ${args["x"]}" } }
            skills { skill<String, String>("s", "stub") { tools("echo") } }
            onToolError("echo") {
                invalidArgs { _, _ ->
                    capturedErrors++
                    null  // Unrecoverable
                }
            }
        }

        try { a("input") } catch (_: Throwable) { /* expected */ }
        assertTrue(capturedErrors >= 1, "untyped invalidArgs path must still fire")
    }
}
