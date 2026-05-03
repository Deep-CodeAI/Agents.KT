package agents_engine.model

import agents_engine.core.agent
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

// Mutation-killer tests for AgenticLoop — see #841.
// Each test cites the file:line and mutator class so a future failure traces
// back to its root cause.
class AgenticLoopMutationTest {

    // executeAgentic — budget boundaries (AgenticLoop.kt L99/L105 ConditionalsBoundary)

    @Test
    fun `maxTurns budget throws on the boundary turn (turns equals maxTurns)`() {
        // L105 `if (turns >= budget.maxTurns)`. Mutating `>=` to `>` would let the
        // loop run one extra turn — 4 LLM calls instead of 3 with maxTurns=3.
        var calls = 0
        val mock = ModelClient { _ ->
            calls++
            LlmResponse.ToolCalls(listOf(ToolCall(name = "noop", arguments = emptyMap())))
        }
        val a = agent<String, String>("a") {
            model { ollama("test"); client = mock }
            budget { maxTurns = 3 }
            tools { tool("noop", "") { _ -> "ok" } }
            skills { skill<String, String>("s", "s") { tools("noop") } }
        }

        val ex = assertThrows<BudgetExceededException> { a("input") }
        assertEquals(BudgetReason.TURNS, ex.reason)
        assertEquals(3, calls, "Expected exactly maxTurns LLM calls before throw — `>=` boundary")
    }

    @Test
    fun `maxToolCalls budget throws on the boundary call (toolCalls equals maxToolCalls)`() {
        var toolInvocations = 0
        val responses = ArrayDeque<LlmResponse>()
        repeat(5) {
            responses.add(LlmResponse.ToolCalls(listOf(ToolCall(name = "tick", arguments = emptyMap()))))
        }
        val mock = ModelClient { _ -> responses.removeFirst() }
        val a = agent<String, String>("a") {
            model { ollama("test"); client = mock }
            budget { maxToolCalls = 2 }
            tools { tool("tick", "") { _ -> toolInvocations++; "t" } }
            skills { skill<String, String>("s", "s") { tools("tick") } }
        }

        val ex = assertThrows<BudgetExceededException> { a("input") }
        assertEquals(BudgetReason.TOOL_CALLS, ex.reason)
        assertEquals(2, toolInvocations, "Expected exactly maxToolCalls executions before throw")
    }

    // executeToolWithBudget — thread cleanup (L213/L214/L216 VoidMethodCall)

    @Test
    fun `per-tool timeout — worker thread is daemon (setDaemon must not be removed)`() {
        // L213 `worker.isDaemon = true`. Removing it would leave the worker non-daemon,
        // which would prevent JVM exit while a runaway tool is still alive.
        val workerWasDaemon = AtomicReference<Boolean?>(null)
        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.ToolCalls(listOf(ToolCall("probe", emptyMap()))))
        responses.add(LlmResponse.Text("done"))
        val mock = ModelClient { _ -> responses.removeFirst() }

        val a = agent<String, String>("a") {
            model { ollama("test"); client = mock }
            budget { perToolTimeout = 5_000.milliseconds }
            tools {
                tool("probe", "") { _ ->
                    workerWasDaemon.set(Thread.currentThread().isDaemon)
                    "ok"
                }
            }
            skills { skill<String, String>("s", "s") { tools("probe") } }
        }

        a("input")
        assertEquals(true, workerWasDaemon.get(), "Tool worker must run on a daemon thread")
    }

    @Test
    fun `per-tool timeout — slow tool is interrupted (interrupt and join must not be removed)`() {
        // L214 `worker.join(timeoutMs)` and L216 `worker.interrupt()`. A tool that
        // overruns the timeout must (a) cause BudgetExceededException with
        // PER_TOOL_TIMEOUT, and (b) the worker must observably receive interrupt.
        val interruptObserved = AtomicReference(false)
        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.ToolCalls(listOf(ToolCall("slow", emptyMap()))))
        val mock = ModelClient { _ -> responses.removeFirst() }

        val a = agent<String, String>("a") {
            model { ollama("test"); client = mock }
            budget { perToolTimeout = 50.milliseconds }
            tools {
                tool("slow", "") { _ ->
                    try {
                        Thread.sleep(10_000)
                    } catch (_: InterruptedException) {
                        interruptObserved.set(true)
                    }
                    "should-never-arrive"
                }
            }
            skills { skill<String, String>("s", "s") { tools("slow") } }
        }

        val ex = assertThrows<BudgetExceededException> { a("input") }
        assertEquals(BudgetReason.PER_TOOL_TIMEOUT, ex.reason)

        // join(timeout) returns when the timeout elapses; the parent throws BEFORE
        // the worker exits. Wait briefly for the worker's InterruptedException catch.
        val deadline = System.currentTimeMillis() + 1000
        while (!interruptObserved.get() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue(interruptObserved.get(), "Slow tool worker should have been interrupted")
    }

    // executeToolWithRecovery — return value (L237 NullReturnVals)

    @Test
    fun `recovered tool result is preserved into next-turn tool message (return value matters)`() {
        // L237 `return recoverInvalidArguments(...)`. NullReturnVals would replace
        // the return with `null`, so the next-turn tool message would be "null"
        // instead of the recovered value. Without asserting the LLM-visible content,
        // the mutation hides.
        val captured = mutableListOf<List<LlmMessage>>()
        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.ToolCalls(listOf(ToolCall("flaky", emptyMap()))))
        responses.add(LlmResponse.Text("done"))
        val mock = ModelClient { msgs -> captured.add(msgs.toList()); responses.removeFirst() }

        var callNumber = 0
        val a = agent<String, String>("a") {
            model { ollama("test"); client = mock }
            tools {
                tool("flaky", "") { _ ->
                    callNumber++
                    if (callNumber == 1) throw RuntimeException("boom")
                    "RECOVERED-VALUE"
                }
            }
            onToolError("flaky") { executionError { _ -> retry(maxAttempts = 2) } }
            skills { skill<String, String>("s", "s") { tools("flaky") } }
        }

        a("input")

        val toolMsg = captured[1].single { it.role == "tool" }
        assertEquals(
            "RECOVERED-VALUE", toolMsg.content,
            "Recovery return value must be preserved through to the LLM tool message",
        )
    }

    // executeToolWithBudget — successful return value (L211/L212/L223 — AtomicReference set + return)

    @Test
    fun `per-tool timeout — successful tool result is preserved through worker AtomicReference`() {
        // Targets executeToolWithBudget$lambda$0 L211/L212 (resultRef.set / errorRef.set)
        // and L223 NullReturnVals on the outer return. If the worker doesn't store the
        // result, or if the outer function returns null instead of resultRef.get(), the
        // tool message becomes "null" and the LLM sees no value.
        val captured = mutableListOf<List<LlmMessage>>()
        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.ToolCalls(listOf(ToolCall("fast", emptyMap()))))
        responses.add(LlmResponse.Text("done"))
        val mock = ModelClient { msgs -> captured.add(msgs.toList()); responses.removeFirst() }

        val a = agent<String, String>("a") {
            model { ollama("test"); client = mock }
            budget { perToolTimeout = 5_000.milliseconds }
            tools { tool("fast", "") { _ -> "FAST-OK" } }
            skills { skill<String, String>("s", "s") { tools("fast") } }
        }

        a("input")
        val toolMsg = captured[1].single { it.role == "tool" }
        assertEquals(
            "FAST-OK", toolMsg.content,
            "Worker must store tool result and parent must return it, even with perToolTimeout active",
        )
    }

    @Test
    fun `per-tool timeout — tool exception is captured and rethrown to caller`() {
        // Targets executeToolWithBudget$lambda$0 L212 (errorRef.set on catch).
        // If the worker doesn't capture the exception, the parent returns null and
        // the failure is silently swallowed.
        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.ToolCalls(listOf(ToolCall("boom", emptyMap()))))
        val mock = ModelClient { _ -> responses.removeFirst() }

        val a = agent<String, String>("a") {
            model { ollama("test"); client = mock }
            budget { perToolTimeout = 5_000.milliseconds }
            tools { tool("boom", "") { _ -> throw RuntimeException("explicit failure") } }
            skills { skill<String, String>("s", "s") { tools("boom") } }
        }

        val ex = assertThrows<RuntimeException> { a("input") }
        assertTrue(
            ex.message!!.contains("explicit failure"),
            "Worker thread exception must propagate to caller, not be swallowed: ${ex.message}",
        )
    }

    // executeToolWithRecovery — typed-args path (L237 NullReturnVals)

    @Test
    fun `typed-args invalidation route preserves recovered value into next-turn tool message`() {
        // Targets AgenticLoop.kt:237 — `return recoverInvalidArguments(...)` for the
        // typed-args path. The call has no invalidArgumentsError, but typed-args
        // construction fails; the invalidArgs handler returns Fixed and the recovered
        // value must reach the LLM. Mutating the return to null hides the value.
        val captured = mutableListOf<List<LlmMessage>>()
        val responses = ArrayDeque<LlmResponse>()
        responses.add(
            LlmResponse.ToolCalls(
                listOf(
                    ToolCall(
                        name = "greet",
                        arguments = mapOf("name" to "world"),  // missing required "language" default OK; force mismatch elsewhere
                    ),
                ),
            ),
        )
        // Force a typed-args failure: pass empty args to a typed tool whose Args has
        // a required `name` field. This makes constructFromMap return null →
        // validateTypedArgsOrNull returns non-null → recoverInvalidArguments runs.
        responses.clear()
        responses.add(LlmResponse.ToolCalls(listOf(ToolCall(name = "greet", arguments = emptyMap()))))
        responses.add(LlmResponse.Text("done"))
        val mock = ModelClient { msgs -> captured.add(msgs.toList()); responses.removeFirst() }

        val a = agent<String, String>("a") {
            model { ollama("test"); client = mock }
            tools {
                tool<GreetArgs, GreetResult>("greet", "Greets") { args -> GreetResult("hi ${args.name}") }
            }
            onToolError("greet") {
                invalidArgs { _, _ -> RepairResult.Fixed("""{"name":"alice","language":"en"}""") }
            }
            skills { skill<String, String>("s", "s") { tools("greet") } }
        }

        a("input")
        val toolMsg = captured[1].single { it.role == "tool" }
        assertTrue(
            toolMsg.content.contains("hi alice"),
            "Recovered typed-args path must deliver executor output to LLM: ${toolMsg.content}",
        )
    }

    // recoverInvalidArguments — RepairResult variants (L309/L331/L332 NegateConditional)

    @Test
    fun `invalidArgs Unrecoverable result throws ToolExecutionException`() {
        // L332 `is RepairResult.Unrecoverable -> throw ...`.
        val responses = ArrayDeque<LlmResponse>()
        responses.add(
            LlmResponse.ToolCalls(
                listOf(
                    ToolCall(
                        name = "tool",
                        rawArguments = """not json""",
                        invalidArgumentsError = "Could not parse",
                    ),
                ),
            ),
        )
        val mock = ModelClient { _ -> responses.removeFirst() }

        val a = agent<String, String>("a") {
            model { ollama("test"); client = mock }
            tools { tool("tool", "") { _ -> "x" } }
            onToolError("tool") {
                invalidArgs { _, _ -> RepairResult.Unrecoverable }
            }
            skills { skill<String, String>("s", "s") { tools("tool") } }
        }

        val ex = assertThrows<ToolExecutionException> { a("input") }
        assertTrue(ex.message!!.contains("unrecoverable"), "Expected 'unrecoverable' in: ${ex.message}")
    }

    @Test
    fun `invalidArgs repair loop is bounded by MAX_ARGUMENT_REPAIR_STEPS`() {
        // L276 ConditionalsBoundary on `repeat(MAX_ARGUMENT_REPAIR_STEPS)`. With the
        // boundary off-by-one, the loop would run 9 instead of 8 iterations. Set both
        // handlers to always return a Fixed-but-still-invalid value so the loop runs
        // to exhaustion, and count total handler invocations.
        var handlerCalls = 0
        val responses = ArrayDeque<LlmResponse>()
        responses.add(
            LlmResponse.ToolCalls(
                listOf(
                    ToolCall(
                        name = "tool",
                        rawArguments = "still not json",
                        invalidArgumentsError = "Could not parse",
                    ),
                ),
            ),
        )
        val mock = ModelClient { _ -> responses.removeFirst() }

        val a = agent<String, String>("a") {
            model { ollama("test"); client = mock }
            tools { tool("tool", "") { _ -> "x" } }
            onToolError("tool") {
                invalidArgs { _, _ -> handlerCalls++; RepairResult.Fixed("still not json") }
                deserializationError { _, _ -> handlerCalls++; RepairResult.Fixed("still not json") }
            }
            skills { skill<String, String>("s", "s") { tools("tool") } }
        }

        assertThrows<ToolExecutionException> { a("input") }
        assertEquals(8, handlerCalls, "Repair loop must call handlers exactly MAX_ARGUMENT_REPAIR_STEPS=8 times")
    }

    @Test
    fun `system prompt skips colon when tool description is empty`() {
        // L75 ConditionalsBoundary on `if (tool.description.isNotEmpty())`. Without a
        // colon-suppression test, the boundary mutation hides — both branches print
        // *something* for the tool name. Assert the exact format with and without desc.
        val captured = mutableListOf<List<LlmMessage>>()
        val mock = ModelClient { msgs -> captured.add(msgs.toList()); LlmResponse.Text("done") }
        val a = agent<String, String>("a") {
            model { ollama("test"); client = mock }
            tools {
                tool("with-desc", "Helpful tool") { _ -> "x" }
                tool("no-desc", "") { _ -> "y" }
            }
            skills { skill<String, String>("s", "s") { tools("with-desc", "no-desc") } }
        }
        a("input")

        val systemMsg = captured.single().first { it.role == "system" }
        assertTrue(
            systemMsg.content.contains("- with-desc: Helpful tool"),
            "Tool with description must include 'name: description': $systemMsg",
        )
        assertTrue(
            systemMsg.content.contains("- no-desc\n"),
            "Tool with empty description must NOT include trailing colon: $systemMsg",
        )
    }

    @Test
    fun `invalidArgs Escalated result feeds severity and reason back to LLM`() {
        // L331 `is RepairResult.Escalated -> return formatEscalatedToolError(...)`.
        val captured = mutableListOf<List<LlmMessage>>()
        val responses = ArrayDeque<LlmResponse>()
        responses.add(
            LlmResponse.ToolCalls(
                listOf(
                    ToolCall(
                        name = "tool",
                        rawArguments = """bad""",
                        invalidArgumentsError = "Could not parse",
                    ),
                ),
            ),
        )
        responses.add(LlmResponse.Text("handled"))
        val mock = ModelClient { msgs -> captured.add(msgs.toList()); responses.removeFirst() }

        val a = agent<String, String>("a") {
            model { ollama("test"); client = mock }
            tools { tool("tool", "") { _ -> "x" } }
            onToolError("tool") {
                invalidArgs { _, _ -> RepairResult.Escalated("schema mismatch", Severity.HIGH) }
            }
            skills { skill<String, String>("s", "s") { tools("tool") } }
        }

        assertEquals("handled", a("input"))
        val toolMsg = captured[1].single { it.role == "tool" }
        assertTrue(toolMsg.content.contains("schema mismatch"), "Got: ${toolMsg.content}")
        assertTrue(toolMsg.content.contains("HIGH"), "Severity missing in: ${toolMsg.content}")
    }
}
