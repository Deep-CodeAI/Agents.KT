package agents_engine.runtime.events

import agents_engine.core.agent
import agents_engine.model.LlmResponse
import agents_engine.model.ModelClient
import agents_engine.model.TokenUsage
import agents_engine.model.BudgetExceededException
import agents_engine.model.BudgetReason
import agents_engine.model.ToolDef
import agents_engine.model.ToolCall
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

// #1737 — integration coverage for the v0.5.0 session surface beyond the
// happy implementedBy path. These pin contracts that step 3 will need to
// preserve when the agentic loop is rewired onto a FlowCollector.

class AgentSessionIntegrationTest {

    @Test
    fun `failure path — Failed terminates events and the same exception rethrows from await`() = runTest {
        val boom = IllegalStateException("boom")
        val failingAgent = agent<String, String>("fails") {
            skills {
                skill<String, String>("explode", "Throws unconditionally") {
                    implementedBy { throw boom }
                }
            }
        }

        val session = failingAgent.session("anything")
        val events = session.events.toList()

        // Terminal event must be Failed — carries the original exception, not a wrapped one.
        assertTrue(events.isNotEmpty(), "expected at least one event before terminal Failed")
        val terminal = events.last()
        assertIs<AgentEvent.Failed>(terminal, "last event must be Failed; got: $terminal")
        assertEquals("fails", terminal.agentId)
        assertSame(boom, terminal.cause, "Failed.cause must be the original exception, not a wrapper")

        // No Completed event must appear — Failed and Completed are mutually exclusive per the premortem.
        assertTrue(events.none { it is AgentEvent.Completed<*> }, "Completed must NOT appear on the failure path")

        // session.await() rethrows an IllegalStateException with the same message.
        // Kotlin coroutines' CompletableDeferred copies the cause with a recovered
        // stack trace before rethrowing, so identity equality doesn't hold here —
        // AgentEvent.Failed.cause carries the original instance (identity-checked
        // above), and await() preserves type + message.
        val thrown = assertFailsWith<IllegalStateException> { session.await() }
        assertEquals(boom.message, thrown.message, "await() must rethrow with the original message")
    }

    @Test
    fun `concurrent sessions — two parallel invocations on the same agent don't share skill-name state`() = runTest {
        val echoAgent = agent<String, String>("echo") {
            skills {
                skill<String, String>("uppercase", "Uppercases the input") {
                    implementedBy { it.uppercase() }
                }
            }
        }

        // Launch two sessions in parallel. The closure-captured skill-name
        // holder is allocated per session.launch{}; if it were shared
        // (e.g., a global var), one session's events could carry the
        // other's skill name (still "uppercase" here — but the test would
        // catch any data-race-induced corruption like a null skill name).
        val (eventsA, outputA, eventsB, outputB) = coroutineScope {
            val sessionA = echoAgent.session("alpha")
            val sessionB = echoAgent.session("bravo")
            val a = async { sessionA.events.toList() }
            val b = async { sessionB.events.toList() }
            val outA = sessionA.await()
            val outB = sessionB.await()
            Quad(a.await(), outA, b.await(), outB)
        }

        assertEquals("ALPHA", outputA)
        assertEquals("BRAVO", outputB)

        for ((label, events) in listOf("A" to eventsA, "B" to eventsB)) {
            assertEquals(3, events.size, "session $label: expected 3 events; got: $events")
            val started = events[0]; assertIs<AgentEvent.SkillStarted>(started)
            assertEquals("uppercase", started.skillName, "session $label: skill name must not be corrupted by the other session")
            val completed = events[1]; assertIs<AgentEvent.SkillCompleted>(completed)
            assertEquals("uppercase", completed.skillName, "session $label: skill name on SkillCompleted")
            assertIs<AgentEvent.Completed<String>>(events[2])
        }
    }

    @Test
    fun `agentic-stub bracketing — Token event fires between SkillStarted and SkillCompleted, ToolCall events absent`() = runTest {
        // Stub model: completes the agentic loop in one turn with a text response.
        // Through the chatOrStream path this becomes a single TextDelta + End,
        // which surfaces as one AgentEvent.Token between the bracket events.
        val usage = TokenUsage(promptTokens = 7, completionTokens = 4)
        val stub = ModelClient { _ -> LlmResponse.Text("done", usage) }

        val agenticAgent = agent<String, String>("agentic") {
            prompt("Test stub agent.")
            model { ollama("llama3"); client = stub }
            skills {
                skill<String, String>("respond", "Echoes back via the model") { tools() }
            }
        }

        val session = agenticAgent.session("kick")
        val events = session.events.toList()
        val output = session.await()

        assertEquals("done", output, "agentic skill output must equal the stub text")

        // No ToolCall* events — this stub has no tool turn.
        assertTrue(
            events.none { it is AgentEvent.ToolCallStarted ||
                it is AgentEvent.ToolCallArgumentsDelta || it is AgentEvent.ToolCallFinished },
            "ToolCall* events must NOT appear when the stub has no tool turn; got: $events",
        )

        // Observability contract: model turn events bracket streaming chunks.
        assertEquals(
            6,
            events.size,
            "expected [SkillStarted, ModelTurnStarted, Token, ModelTurnCompleted, SkillCompleted, Completed]; got: $events",
        )
        val started = events[0]; assertIs<AgentEvent.SkillStarted>(started); assertEquals("respond", started.skillName)
        val turnStarted = events[1]; assertIs<AgentEvent.ModelTurnStarted>(turnStarted)
        assertEquals("agentic", turnStarted.agentId)
        assertEquals("respond", turnStarted.skillName)
        assertEquals(1, turnStarted.turnIndex)
        val token = events[2]; assertIs<AgentEvent.Token>(token)
        assertEquals("agentic", token.agentId)
        assertEquals("respond", token.skillName)
        assertEquals("done", token.text, "the entire stub Text response becomes one Token chunk under default chatStream")
        val turnCompleted = events[3]; assertIs<AgentEvent.ModelTurnCompleted>(turnCompleted)
        assertEquals("text", turnCompleted.responseType)
        assertEquals(usage, turnCompleted.tokensUsed)
        val completed = events[4]; assertIs<AgentEvent.SkillCompleted>(completed); assertEquals("respond", completed.skillName)
        val terminal = events[5]; assertIs<AgentEvent.Completed<String>>(terminal); assertEquals("done", terminal.output)
    }

    @Test
    fun `tool-call events fire around the executor with matching callIds and final Token from the text turn`() = runTest {
        // Stub does two turns:
        //   1. ToolCalls — one call to `greet(name="world")` with explicit callId
        //   2. Text — "hi world"
        // The chatOrStream path translates the first turn into ToolCallStarted +
        // ArgumentsDelta + (provider-side Finished bookkeeping). After the tool
        // executor runs, the loop emits AgentEvent.ToolCallFinished with the
        // result and isError=false.
        val explicitCallId = "call-abc-123"
        val turn1 = LlmResponse.ToolCalls(
            listOf(
                ToolCall(
                    name = "greet",
                    arguments = mapOf("name" to "world"),
                    rawArguments = """{"name":"world"}""",
                    callId = explicitCallId,
                ),
            ),
        )
        val turn2 = LlmResponse.Text("hi world")
        val responses = ArrayDeque<LlmResponse>().apply { add(turn1); add(turn2) }
        val stub = ModelClient { _ -> responses.removeFirst() }

        val toolAgent = agent<String, String>("tool-agent") {
            prompt("Stub agent that issues one tool call then a final text.")
            model { ollama("llama3"); client = stub }
            tools { tool("greet", "Greets someone") { args: Map<String, Any?> -> "hello ${args["name"]}" } }
            skills {
                skill<String, String>("respond", "Uses the greet tool") {
                    @Suppress("DEPRECATION")
                    tools("greet")
                }
            }
        }

        val session = toolAgent.session("kick")
        val events = session.events.toList()
        val output = session.await()

        assertEquals("hi world", output)

        // Find the ToolCallStarted / ToolCallFinished pair — they must share the same callId.
        val started = events.filterIsInstance<AgentEvent.ToolCallStarted>().single()
        val finished = events.filterIsInstance<AgentEvent.ToolCallFinished>().single()
        assertEquals(explicitCallId, started.callId, "explicit callId on ToolCall must flow through to ToolCallStarted")
        assertEquals(started.callId, finished.callId, "ToolCallFinished must share callId with the matching Started")
        assertEquals("greet", finished.toolName)
        assertEquals(mapOf("name" to "world"), finished.arguments)
        assertEquals("hello world", finished.result, "ToolCallFinished.result must carry the executor's return value")
        assertEquals(false, finished.isError, "successful executor return must produce isError=false")

        // ArgumentsDelta at least once for this tool call.
        val argsDeltas = events.filterIsInstance<AgentEvent.ToolCallArgumentsDelta>().filter { it.callId == explicitCallId }
        assertTrue(argsDeltas.isNotEmpty(), "expected at least one ToolCallArgumentsDelta with the same callId; got: $events")

        // Final text turn emits exactly one Token.
        val tokens = events.filterIsInstance<AgentEvent.Token>()
        assertEquals(1, tokens.size, "expected exactly one Token from the final text turn; got: $tokens")
        assertEquals("hi world", tokens.single().text)

        // Order check: Started < ArgumentsDelta < Finished, all before the final Token.
        val startedIdx = events.indexOf(started)
        val finishedIdx = events.indexOf(finished)
        val tokenIdx = events.indexOf(tokens.single())
        assertTrue(startedIdx < finishedIdx, "ToolCallStarted must precede ToolCallFinished")
        assertTrue(finishedIdx < tokenIdx, "ToolCallFinished (from turn 1) must precede the final Token (from turn 2)")
    }

    @Test
    fun `session-aware tool obeys perToolTimeout and emits failed ToolCallFinished`() = runTest {
        val callId = "call-session-timeout"
        val responses = ArrayDeque<LlmResponse>().apply {
            add(
                LlmResponse.ToolCalls(
                    listOf(
                        ToolCall(
                            name = "hang_session",
                            arguments = emptyMap(),
                            rawArguments = "{}",
                            callId = callId,
                        )
                    )
                )
            )
            add(LlmResponse.Text("should not reach second turn"))
        }
        val stub = ModelClient { _ -> responses.removeFirst() }
        val hangingTool = ToolDef(
            name = "hang_session",
            description = "Session-aware tool that never finishes before the per-tool timeout.",
            executor = { _ -> "non-session fallback" },
            sessionExecutor = { _, _ ->
                delay(250.milliseconds)
                "late"
            },
        )

        val toolAgent = agent<String, String>("session-timeout-agent") {
            model { ollama("llama3"); client = stub }
            budget { perToolTimeout = 50.milliseconds }
            tools { +hangingTool }
            skills {
                skill<String, String>("respond", "Calls the session-aware tool") {
                    @Suppress("DEPRECATION")
                    tools("hang_session")
                }
            }
        }

        val session = toolAgent.session("kick")
        val events = session.events.toList()
        val failed = events.filterIsInstance<AgentEvent.Failed>().single()
        val timeout = assertIs<BudgetExceededException>(failed.cause)

        assertEquals(BudgetReason.PER_TOOL_TIMEOUT, timeout.reason)

        val finished = events.filterIsInstance<AgentEvent.ToolCallFinished>().single()
        assertEquals(callId, finished.callId)
        assertEquals("hang_session", finished.toolName)
        assertEquals(true, finished.isError)
        assertTrue(
            finished.result.toString().contains("timeout", ignoreCase = true),
            "timeout marker should be visible in ToolCallFinished.result: ${finished.result}",
        )

        val awaited = assertFailsWith<BudgetExceededException> { session.await() }
        assertEquals(BudgetReason.PER_TOOL_TIMEOUT, awaited.reason)
    }

    @Test
    fun `tokensUsed on SkillCompleted and Completed reflects single-turn stub usage`() = runTest {
        // #1740 — one-turn agentic stub with explicit TokenUsage.
        // Cumulative usage for a one-turn run equals that turn's usage.
        val usage = TokenUsage(promptTokens = 12, completionTokens = 5)
        val stub = ModelClient { _ -> LlmResponse.Text("done", usage) }

        val agentic = agent<String, String>("tu") {
            prompt("Single-turn stub.")
            model { ollama("llama3"); client = stub }
            skills { skill<String, String>("respond", "Echoes via the model") { tools() } }
        }

        val events = agentic.session("kick").events.toList()

        val skillCompleted = events.filterIsInstance<AgentEvent.SkillCompleted>().single()
        val completed = events.filterIsInstance<AgentEvent.Completed<String>>().single()
        assertEquals(usage, skillCompleted.tokensUsed, "SkillCompleted.tokensUsed must reflect the stub's TokenUsage")
        assertEquals(usage, completed.tokensUsed, "Completed.tokensUsed must reflect the stub's TokenUsage")
    }

    @Test
    fun `tokensUsed sums prompt and completion tokens across multiple turns`() = runTest {
        // #1740 — two-turn stub (ToolCalls then Text). Each turn reports
        // distinct usage. Cumulative on SkillCompleted/Completed must sum
        // prompt and completion tokens independently across turns.
        val turn1Usage = TokenUsage(promptTokens = 100, completionTokens = 20)
        val turn2Usage = TokenUsage(promptTokens = 150, completionTokens = 35)
        val turn1 = LlmResponse.ToolCalls(
            listOf(
                ToolCall(
                    name = "ping",
                    arguments = emptyMap(),
                    rawArguments = "{}",
                    callId = "call-multi-turn",
                ),
            ),
            turn1Usage,
        )
        val turn2 = LlmResponse.Text("pong", turn2Usage)
        val responses = ArrayDeque<LlmResponse>().apply { add(turn1); add(turn2) }
        val stub = ModelClient { _ -> responses.removeFirst() }

        val agentic = agent<String, String>("multi") {
            prompt("Two-turn stub.")
            model { ollama("llama3"); client = stub }
            tools { tool("ping", "Returns pong") { _: Map<String, Any?> -> "pong" } }
            skills {
                skill<String, String>("respond", "Two-turn skill") {
                    @Suppress("DEPRECATION")
                    tools("ping")
                }
            }
        }

        val events = agentic.session("kick").events.toList()

        val expected = TokenUsage(
            promptTokens = turn1Usage.promptTokens + turn2Usage.promptTokens,
            completionTokens = turn1Usage.completionTokens + turn2Usage.completionTokens,
        )
        val skillCompleted = events.filterIsInstance<AgentEvent.SkillCompleted>().single()
        val completed = events.filterIsInstance<AgentEvent.Completed<String>>().single()
        assertEquals(expected, skillCompleted.tokensUsed, "SkillCompleted.tokensUsed must sum prompt and completion tokens across turns")
        assertEquals(expected, completed.tokensUsed, "Completed.tokensUsed must sum prompt and completion tokens across turns")
    }

    // Tiny generic 4-tuple — assertable via destructuring in the concurrent test.
    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
}
