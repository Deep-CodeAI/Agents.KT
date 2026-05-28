package agents_engine.core

import agents_engine.model.LlmResponse
import agents_engine.model.ModelClient
import agents_engine.model.Tool
import agents_engine.model.ToolCall
import kotlinx.coroutines.runBlocking
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * #2419 — manifest-hash restore guard. Covers all four paths
 * (matching / Strict / WarnAndProceed / Allow) plus the null-hash carve-out.
 */
class RestoreGuardTest {

    private lateinit var capturing: CapturingHandler
    private val agentLogger: Logger = Logger.getLogger(Agent::class.java.name)

    @BeforeTest
    fun attachHandler() {
        capturing = CapturingHandler()
        // Force the log record through regardless of the JVM's default handler config.
        agentLogger.useParentHandlers = false
        agentLogger.level = Level.ALL
        agentLogger.addHandler(capturing)
    }

    @AfterTest
    fun detachHandler() {
        agentLogger.removeHandler(capturing)
        agentLogger.useParentHandlers = true
    }

    /** Builds a minimal persistent agent with a pre-seeded snapshot in the store. */
    private fun preseed(
        store: SnapshotStore,
        sessionId: String,
        snapshotHash: String?,
    ) {
        store.save(
            sessionId,
            SessionSnapshot(
                messages = listOf(
                    agents_engine.model.LlmMessage("system", "sys"),
                    agents_engine.model.LlmMessage("user", "go"),
                ),
                turns = 1,
                toolCalls = 0,
                toolCallLimit = 32,
                tokensUsed = null,
                memory = emptyMap(),
                requestId = "req-x",
                sessionId = sessionId,
                manifestHash = snapshotHash,
            ),
        )
    }

    private fun guardAgent(
        store: SnapshotStore,
        guard: RestoreGuardPolicy,
        currentHash: String?,
    ): Agent<String, String> {
        val a = agent<String, String>("GuardedAgent") {
            model { ollama("test"); client = ModelClient { _ -> LlmResponse.Text("done") } }
            persistence { this.store = store; restoreGuard = guard }
            skills { skill<String, String>("act", "act") { implementedBy { it } } }
        }
        a.attachManifestHash(currentHash)
        return a
    }

    @Test
    fun `matching hash resumes normally regardless of policy`() {
        val store = InMemorySnapshotStore()
        preseed(store, "s1", "hash-v1")
        // The skill is non-agentic so executeAgentic is not invoked at all —
        // we only need the guard branch to execute and let the run continue.
        val a = guardAgent(store, RestoreGuardPolicy.Strict, currentHash = "hash-v1")
        val out = runBlocking { a.resumeOrStart("s1", "go") }
        assertEquals("go", out)
        assertTrue(capturing.records.none { it.level == Level.WARNING }, "no warnings on match")
    }

    @Test
    fun `mismatch under Strict throws SnapshotManifestMismatchException`() {
        val store = InMemorySnapshotStore()
        preseed(store, "s2", "hash-v1")
        val a = guardAgent(store, RestoreGuardPolicy.Strict, currentHash = "hash-v2")

        val ex = assertFailsWith<SnapshotManifestMismatchException> {
            runBlocking { a.resumeOrStart("s2", "go") }
        }
        assertEquals("s2", ex.sessionId)
        assertEquals("hash-v1", ex.expected)
        assertEquals("hash-v2", ex.actual)
        assertTrue(
            ex.message!!.contains("hash-v1") && ex.message!!.contains("hash-v2"),
            "exception message must include both hashes; was: ${ex.message}",
        )
    }

    @Test
    fun `mismatch under WarnAndProceed logs and continues`() {
        val store = InMemorySnapshotStore()
        preseed(store, "s3", "hash-v1")
        val a = guardAgent(store, RestoreGuardPolicy.WarnAndProceed, currentHash = "hash-v2")

        val out = runBlocking { a.resumeOrStart("s3", "go") }
        assertEquals("go", out, "resume must continue under WarnAndProceed")

        val warning = capturing.records.firstOrNull { it.level == Level.WARNING }
            ?: error("WarnAndProceed must emit at least one WARNING record")
        assertTrue(warning.message.contains("hash-v1"), "warning must mention snapshot hash")
        assertTrue(warning.message.contains("hash-v2"), "warning must mention current hash")
        assertTrue(warning.message.contains("WarnAndProceed"), "warning must explain why it continued")
    }

    @Test
    fun `mismatch under Allow continues silently`() {
        val store = InMemorySnapshotStore()
        preseed(store, "s4", "hash-v1")
        val a = guardAgent(store, RestoreGuardPolicy.Allow, currentHash = "hash-v2")

        val out = runBlocking { a.resumeOrStart("s4", "go") }
        assertEquals("go", out)
        assertTrue(capturing.records.none { it.level == Level.WARNING }, "Allow must not log")
    }

    @Test
    fun `null snapshot hash bypasses the guard under Strict`() {
        // Snapshot pre-dates manifest attachment — we have no enforcement signal.
        // Default behavior is to resume silently rather than throw.
        val store = InMemorySnapshotStore()
        preseed(store, "s5", snapshotHash = null)
        val a = guardAgent(store, RestoreGuardPolicy.Strict, currentHash = "hash-v2")
        val out = runBlocking { a.resumeOrStart("s5", "go") }
        assertEquals("go", out)
    }

    @Test
    fun `null current hash bypasses the guard under Strict`() {
        // The agent never had a manifest computed. Same "no enforcement signal" case.
        val store = InMemorySnapshotStore()
        preseed(store, "s6", "hash-v1")
        val a = guardAgent(store, RestoreGuardPolicy.Strict, currentHash = null)
        val out = runBlocking { a.resumeOrStart("s6", "go") }
        assertEquals("go", out)
    }

    @Test
    fun `fresh start (no snapshot) never triggers the guard`() {
        // With nothing in the store, the guard branch is skipped entirely —
        // resumeOrStart degrades to a regular fresh invocation.
        val store = InMemorySnapshotStore()
        val a = guardAgent(store, RestoreGuardPolicy.Strict, currentHash = "hash-v2")
        val out = runBlocking { a.resumeOrStart("s7", "go") }
        assertEquals("go", out)
    }

    @Test
    fun `restoreGuard defaults to Strict when omitted from the DSL`() {
        val store = InMemorySnapshotStore()
        val a = agent<String, String>("Defaults") {
            model { ollama("test"); client = ModelClient { _ -> LlmResponse.Text("x") } }
            persistence { this.store = store }
            skills { skill<String, String>("act", "act") { implementedBy { it } } }
        }
        assertEquals(RestoreGuardPolicy.Strict, a.persistenceConfig!!.restoreGuard)
    }

    @Test
    fun `Strict guard does not interfere with a clean DSL-driven round-trip`() {
        // Wire the same hash on both ends and prove the matching-hash path works
        // through the full DSL + resumeOrStart flow, not just a pre-seeded snapshot.
        val store = InMemorySnapshotStore()
        val crashing = run {
            val deque = ArrayDeque<LlmResponse>()
            repeat(2) { deque.add(LlmResponse.ToolCalls(listOf(ToolCall("step", emptyMap())))) }
            ModelClient { _ -> if (deque.isEmpty()) error("crash") else deque.removeFirst() }
        }
        val runsA = intArrayOf(0)
        val a1 = agent<String, String>("RoundTrip") {
            lateinit var step: Tool<Map<String, Any?>, Any?>
            model { ollama("test"); client = crashing }
            persistence { this.store = store; restoreGuard = RestoreGuardPolicy.Strict }
            budget { maxTurns = 50; maxToolCalls = 100; maxConsecutiveSameTool = 100 }
            tools { step = tool("step", "") { _ -> runsA[0]++; "ok" } }
            skills { skill<String, String>("run", "run") { tools(step) } }
        }
        a1.attachManifestHash("shared")
        assertFailsWith<IllegalStateException> {
            runBlocking { a1.resumeOrStart("rt", "go") }
        }

        val finishing = run {
            val deque = ArrayDeque<LlmResponse>()
            deque.add(LlmResponse.Text("done"))
            ModelClient { _ -> deque.removeFirst() }
        }
        val a2 = agent<String, String>("RoundTrip") {
            lateinit var step: Tool<Map<String, Any?>, Any?>
            model { ollama("test"); client = finishing }
            persistence { this.store = store; restoreGuard = RestoreGuardPolicy.Strict }
            budget { maxTurns = 50; maxToolCalls = 100; maxConsecutiveSameTool = 100 }
            tools { step = tool("step", "") { _ -> "ok" } }
            skills { skill<String, String>("run", "run") { tools(step) } }
        }
        a2.attachManifestHash("shared")
        val out = runBlocking { a2.resumeOrStart("rt", "go") }
        assertEquals("done", out)
    }

    private class CapturingHandler : Handler() {
        val records = mutableListOf<LogRecord>()
        override fun publish(record: LogRecord?) {
            if (record != null) records += record
        }
        override fun flush() = Unit
        override fun close() = Unit
    }
}
