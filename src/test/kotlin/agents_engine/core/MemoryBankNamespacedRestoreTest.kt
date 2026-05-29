package agents_engine.core

import agents_engine.model.LlmResponse
import agents_engine.model.ModelClient
import agents_engine.model.Tool
import agents_engine.model.ToolCall
import agents_engine.model.executeAgentic
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #2755 — `MemoryBank.restore(state)` clears the entire backing store,
 * which destroys other agents' slots in a shared-bank topology. The
 * snapshot/resume path now uses [MemoryBank.snapshotForAgent] /
 * [MemoryBank.restoreForAgent] to touch only the resuming agent's slot.
 *
 * Tests pin:
 * 1. Direct-API: snapshot/restoreForAgent does not touch other slots.
 * 2. End-to-end: resume through the `executeAgentic` seam only restores
 *    this agent's slot in a shared MemoryBank.
 */
class MemoryBankNamespacedRestoreTest {

    @Test
    fun `snapshotForAgent returns only the named slot`() {
        val bank = MemoryBank().apply {
            write("ActorsAgent", "a-state")
            write("MerchAgent", "m-state")
        }
        assertEquals("a-state", bank.snapshotForAgent("ActorsAgent"))
        assertEquals("m-state", bank.snapshotForAgent("MerchAgent"))
        assertNull(bank.snapshotForAgent("never-wrote"))
    }

    @Test
    fun `restoreForAgent only touches the named slot, siblings preserved`() {
        val bank = MemoryBank().apply {
            write("ActorsAgent", "a-original")
            write("MerchAgent", "m-original")
        }
        bank.restoreForAgent("ActorsAgent", "a-replayed")
        assertEquals("a-replayed", bank.read("ActorsAgent"))
        assertEquals("m-original", bank.read("MerchAgent"), "sibling slot must be untouched")
    }

    @Test
    fun `restoreForAgent with null clears just that slot`() {
        val bank = MemoryBank().apply {
            write("ActorsAgent", "a-state")
            write("MerchAgent", "m-state")
        }
        bank.restoreForAgent("ActorsAgent", null)
        assertEquals("", bank.read("ActorsAgent"), "cleared slot reads as empty")
        assertEquals("m-state", bank.read("MerchAgent"), "sibling slot preserved")
    }

    @Test
    fun `end-to-end - resuming one session in a shared bank does not wipe the other agent's slot`() {
        val sharedBank = MemoryBank()
        sharedBank.write("OtherAgent", "other-untouched")

        // ActorsAgent runs one tool turn, snapshots, then resume restores its slot.
        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.ToolCalls(listOf(ToolCall("step", emptyMap()))))
        responses.add(LlmResponse.Text("done"))
        val mock = ModelClient { _ -> responses.removeFirst() }

        val captured = arrayOfNulls<SessionSnapshot>(1)
        val a = agent<String, String>("ActorsAgent") {
            lateinit var step: Tool<Map<String, Any?>, Any?>
            model { ollama("test"); client = mock }
            memory(sharedBank)
            tools { step = tool("step", "") { _ -> sharedBank.write("ActorsAgent", "actors-state"); "ok" } }
            skills { skill<String, String>("act", "act") { tools(step) } }
        }
        val skillA = a.skills.values.first()

        runBlocking {
            executeAgentic(a, skillA, "go", onTurnCheckpoint = { s -> captured[0] = s })
        }

        val snap = assertNotNull(captured[0])
        assertTrue(
            snap.memory.containsKey("ActorsAgent") && !snap.memory.containsKey("OtherAgent"),
            "snapshot must contain ONLY the resuming agent's slot, got keys=${snap.memory.keys}",
        )

        // Corrupt actors' slot, then verify resume restores it without touching the other slot.
        sharedBank.write("ActorsAgent", "corrupted")
        sharedBank.write("OtherAgent", "other-still-here")

        val resumeResponses = ArrayDeque<LlmResponse>().apply { add(LlmResponse.Text("resumed")) }
        val resumeMock = ModelClient { _ -> resumeResponses.removeFirst() }

        val b = agent<String, String>("ActorsAgent") {
            lateinit var step: Tool<Map<String, Any?>, Any?>
            model { ollama("test"); client = resumeMock }
            memory(sharedBank)
            tools { step = tool("step", "") { _ -> "ok" } }
            skills { skill<String, String>("act", "act") { tools(step) } }
        }
        val skillB = b.skills.values.first()

        runBlocking { executeAgentic(b, skillB, "go", resumeFrom = snap) }

        assertEquals("actors-state", sharedBank.read("ActorsAgent"), "actor's slot restored from snapshot")
        assertEquals("other-still-here", sharedBank.read("OtherAgent"), "other agent's slot must NOT be wiped")
    }
}
