package agents_engine.core

import agents_engine.model.BudgetCheckpointException
import agents_engine.model.BudgetDecision
import agents_engine.model.BudgetExceededException
import agents_engine.model.BudgetReason
import agents_engine.model.LlmResponse
import agents_engine.model.ModelClient
import agents_engine.model.Tool
import agents_engine.model.ToolCall
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #2749 — public snapshot/resume seam + budget-aware Checkpoint.
 *
 * Pins each acceptance criterion from the upstream proposal:
 *
 * 1. `invokeSuspendResuming(input)` with defaults behaves identically to
 *    `invokeSuspend(input)` — backward-compatible.
 * 2. `onTurnCheckpoint` fires per turn boundary.
 * 3. `resumeFrom = snapshot` continues without replaying prior tool calls.
 * 4. `BudgetDecision.Checkpoint` returned from `onBudgetExceeded`:
 *    a. Delivers the snapshot via `onTurnCheckpoint`.
 *    b. Throws `BudgetCheckpointException` carrying the same snapshot.
 *    c. The exception IS a `BudgetExceededException` (existing catch
 *       blocks still fire).
 * 5. Resuming with a raised budget continues from the checkpoint.
 * 6. `Checkpoint` without an `onTurnCheckpoint` falls back to Stop
 *    semantics (regular `BudgetExceededException`).
 */
class InvokeSuspendResumingTest {

    @Test
    fun `invokeSuspendResuming with defaults matches invokeSuspend behavior`() {
        val model = ModelClient { _ -> LlmResponse.Text("hi") }
        val a = agent<String, String>("Echo") {
            model { ollama("test"); client = model }
            skills { skill<String, String>("s", "stub") { implementedBy { "ok" } } }
        }
        val direct = runBlocking { a.invokeSuspend("anything") }
        val resuming = runBlocking { a.invokeSuspendResuming("anything") }
        assertEquals(direct, resuming, "no-op resume must match invokeSuspend output")
    }

    @Test
    fun `onTurnCheckpoint fires at each turn boundary with the in-flight snapshot`() {
        // Model: 2 tool turns, then a final text response. The hook should
        // fire after each tool round (2 times) — turn boundaries are post-
        // tool-execution, before the next model call.
        val mock = run {
            val deque = ArrayDeque<LlmResponse>()
            repeat(2) { deque.add(LlmResponse.ToolCalls(listOf(ToolCall("step", emptyMap())))) }
            deque.add(LlmResponse.Text("done"))
            ModelClient { _ -> deque.removeFirst() }
        }
        val snaps = mutableListOf<SessionSnapshot>()

        val a = agent<String, String>("Stepper") {
            lateinit var step: Tool<Map<String, Any?>, Any?>
            model { ollama("test"); client = mock }
            tools { step = tool("step", "") { _ -> "ok" } }
            skills { skill<String, String>("act", "act") { tools(step) } }
        }

        val out = runBlocking {
            a.invokeSuspendResuming(
                input = "go",
                onTurnCheckpoint = { snap -> snaps += snap },
            )
        }
        assertEquals("done", out)
        assertEquals(2, snaps.size, "one checkpoint per completed tool round")
        // Each successive snapshot has a higher turn / toolCalls count.
        assertTrue(snaps[0].turns <= snaps[1].turns)
        assertTrue(snaps[0].toolCalls < snaps[1].toolCalls)
    }

    @Test
    fun `resumeFrom continues without replaying prior tools`() {
        // --- Run A: 3 tool turns, model crashes; capture the checkpoint. ---
        val crashing = run {
            val deque = ArrayDeque<LlmResponse>()
            repeat(3) { deque.add(LlmResponse.ToolCalls(listOf(ToolCall("step", emptyMap())))) }
            ModelClient { _ -> if (deque.isEmpty()) error("simulated crash") else deque.removeFirst() }
        }
        val runsA = intArrayOf(0)
        val agentA = agent<String, String>("Resumable") {
            lateinit var step: Tool<Map<String, Any?>, Any?>
            model { ollama("test"); client = crashing }
            budget { maxTurns = 50; maxToolCalls = 100; maxConsecutiveSameTool = 100 }
            tools { step = tool("step", "") { _ -> runsA[0]++; "ok" } }
            skills { skill<String, String>("act", "act") { tools(step) } }
        }
        val captured = mutableListOf<SessionSnapshot>()
        assertFailsWith<IllegalStateException> {
            runBlocking {
                agentA.invokeSuspendResuming(
                    input = "go",
                    onTurnCheckpoint = { snap -> captured += snap },
                )
            }
        }
        assertEquals(3, runsA[0], "A ran 3 tool calls before the crash")
        val resumeSeed = captured.last()

        // --- Run B: fresh agent, resumeFrom the captured snapshot, finish. ---
        var firstMessages: List<agents_engine.model.LlmMessage>? = null
        val finishing = run {
            val deque = ArrayDeque<LlmResponse>()
            repeat(2) { deque.add(LlmResponse.ToolCalls(listOf(ToolCall("step", emptyMap())))) }
            deque.add(LlmResponse.Text("done"))
            ModelClient { msgs ->
                if (firstMessages == null) firstMessages = msgs.toList()
                deque.removeFirst()
            }
        }
        val runsB = intArrayOf(0)
        val agentB = agent<String, String>("Resumable") {
            lateinit var step: Tool<Map<String, Any?>, Any?>
            model { ollama("test"); client = finishing }
            budget { maxTurns = 50; maxToolCalls = 100; maxConsecutiveSameTool = 100 }
            tools { step = tool("step", "") { _ -> runsB[0]++; "ok" } }
            skills { skill<String, String>("act", "act") { tools(step) } }
        }

        val out = runBlocking { agentB.invokeSuspendResuming("go", resumeFrom = resumeSeed) }
        assertEquals("done", out)
        assertEquals(2, runsB[0], "B ran only the remaining 2 tool calls (no replay)")

        // B's first model call saw A's full restored history.
        val seen = assertNotNull(firstMessages)
        assertTrue(seen.any { it.role == "user" && it.content == "go" })
        assertEquals(3, seen.count { it.role == "tool" })
    }

    @Test
    fun `BudgetDecision Checkpoint delivers snapshot via hook and throws BudgetCheckpointException`() {
        // Loop hits the tool-call cap; handler returns Checkpoint; hook
        // receives the in-flight snapshot AND the exception carries the
        // same snapshot on its .snapshot field.
        val mock = ModelClient { _ ->
            LlmResponse.ToolCalls(listOf(ToolCall("step", emptyMap())))
        }
        var hookCalled = 0
        var hookSnapshot: SessionSnapshot? = null

        val a = agent<String, String>("Capper") {
            lateinit var step: Tool<Map<String, Any?>, Any?>
            model { ollama("test"); client = mock }
            budget { maxTurns = 50; maxToolCalls = 2; maxConsecutiveSameTool = 100 }
            tools { step = tool("step", "") { _ -> "ok" } }
            skills { skill<String, String>("act", "act") { tools(step) } }
            onBudgetExceeded { _, _ -> BudgetDecision.Checkpoint }
        }

        val ex = assertFailsWith<BudgetCheckpointException> {
            runBlocking {
                a.invokeSuspendResuming(
                    input = "go",
                    onTurnCheckpoint = { snap ->
                        // The hook fires per turn boundary AND once more
                        // at the Checkpoint site. Capture the latest.
                        hookCalled++
                        hookSnapshot = snap
                    },
                )
            }
        }

        assertEquals(BudgetReason.TOOL_CALLS, ex.reason)
        assertEquals(2, ex.currentLimit, "carries the breach limit verbatim")
        assertNotNull(ex.snapshot, "exception MUST carry the snapshot field")
        assertEquals(2, ex.snapshot.toolCalls, "snapshot reflects the cap state")
        // Hook fired with the Checkpoint snapshot — and it's the SAME instance.
        assertTrue(hookCalled >= 1)
        assertEquals(ex.snapshot, hookSnapshot, "hook receives the same snapshot the exception carries")
    }

    @Test
    fun `BudgetCheckpointException is catchable as BudgetExceededException`() {
        val mock = ModelClient { _ -> LlmResponse.ToolCalls(listOf(ToolCall("step", emptyMap()))) }
        val a = agent<String, String>("Compat") {
            lateinit var step: Tool<Map<String, Any?>, Any?>
            model { ollama("test"); client = mock }
            budget { maxTurns = 50; maxToolCalls = 1; maxConsecutiveSameTool = 100 }
            tools { step = tool("step", "") { _ -> "ok" } }
            skills { skill<String, String>("act", "act") { tools(step) } }
            onBudgetExceeded { _, _ -> BudgetDecision.Checkpoint }
        }

        // Pre-existing catch (BudgetExceededException) blocks MUST still
        // fire — that's the subclass-compat contract.
        val caught = try {
            runBlocking {
                a.invokeSuspendResuming(input = "go", onTurnCheckpoint = { /* discard */ })
            }
            null
        } catch (e: BudgetExceededException) {
            e
        }
        val budget = assertNotNull(caught, "BudgetCheckpointException must be catchable via BudgetExceededException")
        assertTrue(budget is BudgetCheckpointException, "but the actual type is the checkpoint subclass")
    }

    @Test
    fun `resume with a raised budget continues from the checkpoint without history replay`() {
        // The full UX: cap fires → Checkpoint → user "raises the cap" →
        // resume continues. B should run ONLY the remaining 2 tool calls
        // and reach "done", with the original 2 tool calls NOT re-executed.
        val mock = ModelClient { _ ->
            LlmResponse.ToolCalls(listOf(ToolCall("step", emptyMap())))
        }
        var checkpointSnap: SessionSnapshot? = null
        val runsA = intArrayOf(0)
        val agentA = agent<String, String>("RaiseAndContinue") {
            lateinit var step: Tool<Map<String, Any?>, Any?>
            model { ollama("test"); client = mock }
            budget { maxTurns = 50; maxToolCalls = 2; maxConsecutiveSameTool = 100 }
            tools { step = tool("step", "") { _ -> runsA[0]++; "ok" } }
            skills { skill<String, String>("act", "act") { tools(step) } }
            onBudgetExceeded { _, _ -> BudgetDecision.Checkpoint }
        }

        val ex = assertFailsWith<BudgetCheckpointException> {
            runBlocking {
                agentA.invokeSuspendResuming(
                    input = "go",
                    onTurnCheckpoint = { snap -> checkpointSnap = snap },
                )
            }
        }
        assertEquals(2, runsA[0])
        val seed = assertNotNull(ex.snapshot)
        assertEquals(seed, checkpointSnap)

        // "User accepts the raise" — a fresh agent with the same name + a
        // bigger budget resumes from the captured snapshot.
        val finishing = run {
            val deque = ArrayDeque<LlmResponse>()
            repeat(2) { deque.add(LlmResponse.ToolCalls(listOf(ToolCall("step", emptyMap())))) }
            deque.add(LlmResponse.Text("done"))
            ModelClient { _ -> deque.removeFirst() }
        }
        val runsB = intArrayOf(0)
        val agentB = agent<String, String>("RaiseAndContinue") {
            lateinit var step: Tool<Map<String, Any?>, Any?>
            model { ollama("test"); client = finishing }
            budget { maxTurns = 50; maxToolCalls = 10; maxConsecutiveSameTool = 100 }  // raised!
            tools { step = tool("step", "") { _ -> runsB[0]++; "ok" } }
            skills { skill<String, String>("act", "act") { tools(step) } }
        }
        val out = runBlocking { agentB.invokeSuspendResuming("go", resumeFrom = seed) }
        assertEquals("done", out)
        assertEquals(2, runsB[0], "only the 2 remaining tool calls ran — original 2 were NOT re-executed")
    }

    @Test
    fun `Checkpoint without an onTurnCheckpoint hook falls back to Stop semantics`() {
        // If the handler asks for Checkpoint but the caller didn't wire a
        // way to receive the snapshot, silently swallowing the breach
        // would be worse than throwing — fall back to regular
        // BudgetExceededException.
        val mock = ModelClient { _ -> LlmResponse.ToolCalls(listOf(ToolCall("step", emptyMap()))) }
        val a = agent<String, String>("NoSink") {
            lateinit var step: Tool<Map<String, Any?>, Any?>
            model { ollama("test"); client = mock }
            budget { maxTurns = 50; maxToolCalls = 1; maxConsecutiveSameTool = 100 }
            tools { step = tool("step", "") { _ -> "ok" } }
            skills { skill<String, String>("act", "act") { tools(step) } }
            onBudgetExceeded { _, _ -> BudgetDecision.Checkpoint }  // but no hook below
        }

        val ex = assertFailsWith<BudgetExceededException> {
            runBlocking { a.invokeSuspendResuming("go") }  // onTurnCheckpoint = null
        }
        // The thrown exception is the plain Stop-style, NOT a
        // BudgetCheckpointException — there was nowhere to deliver the
        // snapshot, so the runtime treats Checkpoint-without-sink as Stop.
        assertTrue(ex !is BudgetCheckpointException, "no sink → no Checkpoint exception; got ${ex::class.simpleName}")
        assertEquals(BudgetReason.TOOL_CALLS, ex.reason)
    }

    @Test
    fun `passing an onTurnCheckpoint with no Checkpoint decision still fires per-turn checkpoints`() {
        // Sanity: the hook is the per-turn checkpoint mechanism INDEPENDENT
        // of the Checkpoint variant. With Extend or no breach, the hook
        // still fires per turn boundary.
        val mock = run {
            val deque = ArrayDeque<LlmResponse>()
            repeat(2) { deque.add(LlmResponse.ToolCalls(listOf(ToolCall("step", emptyMap())))) }
            deque.add(LlmResponse.Text("done"))
            ModelClient { _ -> deque.removeFirst() }
        }
        var hookCount = 0
        val a = agent<String, String>("NoBreachJustHook") {
            lateinit var step: Tool<Map<String, Any?>, Any?>
            model { ollama("test"); client = mock }
            tools { step = tool("step", "") { _ -> "ok" } }
            skills { skill<String, String>("act", "act") { tools(step) } }
        }

        runBlocking {
            a.invokeSuspendResuming(
                input = "go",
                onTurnCheckpoint = { hookCount++ },
            )
        }
        assertEquals(2, hookCount, "hook fires for each tool round, not for the final text turn")
    }

    @Test
    fun `defaults do not break non-agentic implementedBy skills`() {
        // Non-agentic skills don't go through executeAgentic, so the
        // resumeFrom / onTurnCheckpoint parameters are no-ops for them.
        // Pin that they don't blow up.
        val a = agent<String, String>("Implemented") {
            skills { skill<String, String>("s", "stub") { implementedBy { it.reversed() } } }
        }
        var hookCount = 0
        val out = runBlocking {
            a.invokeSuspendResuming(
                input = "abc",
                onTurnCheckpoint = { hookCount++ },
            )
        }
        assertEquals("cba", out)
        assertEquals(0, hookCount, "implementedBy skills don't run the agentic loop → no checkpoints")
    }
}
