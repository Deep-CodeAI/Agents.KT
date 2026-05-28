package agents_engine.core

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
 * #2418 — Phase-2 ergonomic seam on top of the #2416 spike. The v1 test
 * (`agents_engine.model.SnapshotResumeTest`) drives `executeAgentic` directly;
 * these tests pin the public path: `agent { persistence { … } }` +
 * `agent.resumeOrStart(sessionId, input)`. The internals it relies on
 * (auto-checkpoint at turn boundary, resumeFrom seeding) are already covered
 * by the v1 spike — here we exercise the user-facing API surface.
 */
class PersistenceDslTest {

    @Test
    fun `resumeOrStart with no prior snapshot starts a fresh run`() {
        val backing = InMemorySnapshotStore()
        val sessionId = "user-1"
        // First reply is a tool call; second is a final answer. Two turns total.
        val model = run {
            val deque = ArrayDeque<LlmResponse>()
            deque.add(LlmResponse.ToolCalls(listOf(ToolCall("step", emptyMap()))))
            deque.add(LlmResponse.Text("done"))
            ModelClient { _ -> deque.removeFirst() }
        }
        val toolRuns = intArrayOf(0)
        val a = agent<String, String>("FreshRun") {
            lateinit var step: Tool<Map<String, Any?>, Any?>
            model { ollama("test"); client = model }
            persistence { store = backing }
            tools { step = tool("step", "") { _ -> toolRuns[0]++; "ok" } }
            skills { skill<String, String>("act", "act") { tools(step) } }
        }

        assertNull(backing.load(sessionId), "store starts empty")
        val out = runBlocking { a.resumeOrStart(sessionId, "go") }

        assertEquals("done", out)
        assertEquals(1, toolRuns[0])
        // One turn boundary completed (the tool round before the final text reply),
        // so the auto-checkpoint should have written exactly one snapshot.
        val saved = assertNotNull(backing.load(sessionId), "auto-checkpoint must persist the turn")
        assertEquals(sessionId, saved.sessionId)
        assertEquals(1, saved.toolCalls)
    }

    @Test
    fun `resumeOrStart picks up a prior snapshot and continues the conversation`() {
        // --- Run A: model crashes after 3 tool-turns; persistence saves each turn. ---
        val sessionId = "user-42"
        val backing = InMemorySnapshotStore()
        val crashing = run {
            val deque = ArrayDeque<LlmResponse>()
            repeat(3) { deque.add(LlmResponse.ToolCalls(listOf(ToolCall("step", emptyMap())))) }
            ModelClient { _ -> if (deque.isEmpty()) error("simulated crash") else deque.removeFirst() }
        }
        val runsA = intArrayOf(0)
        val agentA = agent<String, String>("ResumerA") {
            lateinit var step: Tool<Map<String, Any?>, Any?>
            model { ollama("test"); client = crashing }
            persistence { store = backing }
            budget { maxTurns = 50; maxToolCalls = 100; maxConsecutiveSameTool = 100 }
            tools { step = tool("step", "") { _ -> runsA[0]++; "ok" } }
            skills { skill<String, String>("act", "act") { tools(step) } }
        }
        assertFailsWith<IllegalStateException> {
            runBlocking { agentA.resumeOrStart(sessionId, "go") }
        }
        assertEquals(3, runsA[0])
        val saved = assertNotNull(backing.load(sessionId))
        assertEquals(3, saved.toolCalls, "snapshot reflects 3 completed tool calls")

        // --- Run B: brand-new agent with the same sessionId — must continue, not restart. ---
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
        val agentB = agent<String, String>("ResumerA") {
            lateinit var step: Tool<Map<String, Any?>, Any?>
            model { ollama("test"); client = finishing }
            persistence { store = backing }
            budget { maxTurns = 50; maxToolCalls = 100; maxConsecutiveSameTool = 100 }
            tools { step = tool("step", "") { _ -> runsB[0]++; "ok" } }
            skills { skill<String, String>("act", "act") { tools(step) } }
        }

        val out = runBlocking { agentB.resumeOrStart(sessionId, "go") }
        assertEquals("done", out)
        assertEquals(2, runsB[0], "B ran only the remaining 2 tool calls")

        // B's first model call saw the restored history — system + original user
        // input + A's 3 tool results, not a fresh "go".
        val seen = assertNotNull(firstMessages)
        assertEquals("system", seen.first().role)
        assertTrue(seen.any { it.role == "user" && it.content == "go" })
        assertEquals(3, seen.count { it.role == "tool" })
    }

    @Test
    fun `resumeOrStart fails fast when persistence is not configured`() {
        val a = agent<String, String>("Unpersisted") {
            model { ollama("test"); client = ModelClient { _ -> LlmResponse.Text("ok") } }
            skills { skill<String, String>("act", "act") { } }
        }
        val ex = assertFailsWith<IllegalStateException> {
            runBlocking { a.resumeOrStart("any", "go") }
        }
        assertTrue(
            ex.message!!.contains("no persistence configured"),
            "error must point the user at the missing persistence { } block; was: ${ex.message}",
        )
    }

    @Test
    fun `persistence block requires a store`() {
        // Missing store = … must fail at agent-construction time, not at invoke time —
        // builds an agent with `persistence { autoSnapshot = OnTurnComplete }` and no store.
        val ex = assertFailsWith<IllegalStateException> {
            agent<String, String>("BadConfig") {
                model { ollama("test"); client = ModelClient { _ -> LlmResponse.Text("x") } }
                persistence { autoSnapshot = AutoSnapshotPolicy.OnTurnComplete }
                skills { skill<String, String>("act", "act") { } }
            }
        }
        assertTrue(
            ex.message!!.contains("requires a store"),
            "error must call out the missing store assignment; was: ${ex.message}",
        )
    }

    @Test
    fun `invokeSuspend without persistence config behaves byte-for-byte as before`() {
        // No persistence block → no checkpoint, no resume. This is the existing
        // public surface; this test pins the no-behavior-change guarantee.
        val a = agent<String, String>("Unconfigured") {
            model { ollama("test"); client = ModelClient { _ -> LlmResponse.Text("hi") } }
            skills { skill<String, String>("act", "act") { implementedBy { it.uppercase() } } }
        }
        assertEquals("ANYTHING", runBlocking { a.invokeSuspend("anything") })
        assertNull(a.persistenceConfig, "persistenceConfig stays null when DSL block is omitted")
    }

    @Test
    fun `Disabled auto-snapshot policy skips the turn-boundary checkpoint`() {
        // With AutoSnapshotPolicy.Disabled, the loop runs but the store stays empty —
        // the seam for future manual Agent.snapshot(...) calls.
        val backing = InMemorySnapshotStore()
        val sessionId = "user-disabled"
        val model = run {
            val deque = ArrayDeque<LlmResponse>()
            deque.add(LlmResponse.ToolCalls(listOf(ToolCall("step", emptyMap()))))
            deque.add(LlmResponse.Text("done"))
            ModelClient { _ -> deque.removeFirst() }
        }
        val a = agent<String, String>("DisabledAuto") {
            lateinit var step: Tool<Map<String, Any?>, Any?>
            model { ollama("test"); client = model }
            persistence { store = backing; autoSnapshot = AutoSnapshotPolicy.Disabled }
            tools { step = tool("step", "") { _ -> "ok" } }
            skills { skill<String, String>("act", "act") { tools(step) } }
        }
        runBlocking { a.resumeOrStart(sessionId, "go") }
        assertNull(backing.load(sessionId), "Disabled policy must not write snapshots automatically")
    }
}
