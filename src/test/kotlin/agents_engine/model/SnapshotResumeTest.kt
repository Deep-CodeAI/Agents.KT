package agents_engine.model

import agents_engine.core.FileSnapshotStore
import agents_engine.core.InMemorySnapshotStore
import agents_engine.core.MemoryBank
import agents_engine.core.SessionSnapshot
import agents_engine.core.agent
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * #2416 — snapshot/resume v1 spike. Proves the design hinge: an agent's
 * resumable state is its message history + counters, so **resume = re-enter the
 * loop seeded with a snapshot**, not suspend a coroutine. The round-trip test is
 * the acceptance gate.
 */
class SnapshotResumeTest {

    @Test
    fun `a fresh agent resumes the conversation from a turn-boundary snapshot`() {
        val sessionId = "user-42"
        val store = InMemorySnapshotStore()
        val snaps = mutableListOf<SessionSnapshot>()

        // --- Agent A: 3 tool-turns, checkpointing each, then the model "crashes". ---
        val bankA = MemoryBank()
        val runsA = intArrayOf(0)
        val crashingModel = run {
            val deque = ArrayDeque<LlmResponse>()
            repeat(3) { deque.add(LlmResponse.ToolCalls(listOf(ToolCall("step", emptyMap())))) }
            ModelClient { _ -> if (deque.isEmpty()) error("simulated process crash") else deque.removeFirst() }
        }
        val agentA = agent<String, String>("ActorsAgent") {
            lateinit var step: Tool<Map<String, Any?>, Any?>
            model { ollama("test"); client = crashingModel }
            memory(bankA)
            budget { maxTurns = 50; maxToolCalls = 100; maxConsecutiveSameTool = 100 }
            tools { step = tool("step", "") { _ -> runsA[0]++; bankA.write("ActorsAgent", "progress=${runsA[0]}"); "ok" } }
            skills { skill<String, String>("act", "act") { tools(step) } }
        }
        val skillA = agentA.skills.values.first()

        assertFailsWith<IllegalStateException> {
            runBlocking {
                executeAgentic(agentA, skillA, "go", onTurnCheckpoint = { s -> snaps += s; store.save(sessionId, s) })
            }
        }
        assertEquals(3, snaps.size, "one checkpoint per completed tool-turn")
        assertEquals(3, runsA[0], "A ran 3 tool calls before crashing")

        // --- Agent B: brand-new instance (simulating a restart), resumes + finishes. ---
        val bankB = MemoryBank()
        val runsB = intArrayOf(0)
        var firstSeenMessages: List<LlmMessage>? = null
        val finishingModel = run {
            val deque = ArrayDeque<LlmResponse>()
            repeat(2) { deque.add(LlmResponse.ToolCalls(listOf(ToolCall("step", emptyMap())))) }
            deque.add(LlmResponse.Text("done"))
            ModelClient { msgs -> if (firstSeenMessages == null) firstSeenMessages = msgs.toList(); deque.removeFirst() }
        }
        val agentB = agent<String, String>("ActorsAgent") {
            lateinit var step: Tool<Map<String, Any?>, Any?>
            model { ollama("test"); client = finishingModel }
            memory(bankB)
            budget { maxTurns = 50; maxToolCalls = 100; maxConsecutiveSameTool = 100 }
            tools { step = tool("step", "") { _ -> runsB[0]++; "ok" } }
            skills { skill<String, String>("act", "act") { tools(step) } }
        }
        val skillB = agentB.skills.values.first()

        val resumed = assertNotNull(store.load(sessionId), "snapshot should be in the store")
        val result = runBlocking { executeAgentic(agentB, skillB, "go", resumeFrom = resumed) }

        assertEquals("done", result.output, "B continues to completion")
        assertEquals(2, runsB[0], "B ran only the remaining 2 tool calls")

        // The conversation was restored: B's first model call saw A's full history.
        val seen = assertNotNull(firstSeenMessages)
        assertEquals("system", seen.first().role)
        assertTrue(seen.any { it.role == "user" && it.content == "go" }, "original input restored")
        assertEquals(3, seen.count { it.role == "tool" }, "A's 3 tool results restored")

        // Memory was restored from the snapshot into B's fresh bank.
        assertEquals("progress=3", bankB.read("ActorsAgent"))
    }

    @Test
    @Suppress("DEPRECATION")
    fun `MemoryBank snapshot and restore round-trips (legacy wipe-all path)`() {
        // #2755 — the wipe-all restore() is deprecated; this test still pins
        // its behavior because it remains the Snapshotable<Map<String,String>>
        // contract. Snapshot/resume callers should use restoreForAgent.
        val a = MemoryBank().apply { write("agent", "line one\nline two") }
        val state = a.snapshot()

        val b = MemoryBank()
        b.restore(state)
        assertEquals("line one\nline two", b.read("agent"))
        assertEquals(state, b.snapshot())
    }

    @Test
    fun `FileSnapshotStore round-trips a snapshot through JSON`(@TempDir dir: File) {
        val snap = SessionSnapshot(
            messages = listOf(
                LlmMessage("system", "you are helpful"),
                LlmMessage("user", "go"),
                LlmMessage("assistant", "", listOf(ToolCall("step", mapOf("city" to "NYC")))),
                LlmMessage("tool", "ok"),
            ),
            turns = 2,
            toolCalls = 1,
            toolCallLimit = 32,
            tokensUsed = TokenUsage(promptTokens = 10, completionTokens = 5, provider = "ollama", model = "test"),
            memory = mapOf("ActorsAgent" to "progress=1"),
            requestId = "req-1",
            sessionId = "user-42",
            manifestHash = null,
        )
        val store = FileSnapshotStore(dir.toPath())

        store.save("user-42", snap)
        val loaded = store.load("user-42")

        assertEquals(snap, loaded, "snapshot must survive the JSON round-trip intact")
        assertEquals(null, store.load("absent"), "missing key loads null")
    }
}
