package agents_engine.model

import agents_engine.core.AgentRuntimeContext
import agents_engine.core.SessionSnapshot
import agents_engine.core.SnapshotManifestMismatchException
import agents_engine.core.agent
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * #3376 batch 4 — pins the resume/HITL restore contract extracted from `executeAgentic`'s inline
 * `if (resumeFrom != null)` block into [restoreFromSnapshot]. The manifest-hash fail-closed guard
 * (#2754) and message restore were only reachable through a full resuming invocation; now directly
 * testable.
 */
class SnapshotRestoreTest {

    private fun snapshot(manifestHash: String?, messages: List<LlmMessage> = emptyList()) =
        SessionSnapshot(
            messages = messages,
            turns = 0,
            toolCalls = 0,
            toolCallLimit = 32,
            tokensUsed = null,
            memory = emptyMap(),
            requestId = "r",
            sessionId = null,
            manifestHash = manifestHash,
        )

    @Test
    fun `restoreFromSnapshot fails closed on a manifest-hash mismatch`() {
        val agent = agent<String, String>("a") {
            skills { skill<String, String>("s", "d") { implementedBy { it } } }
        } // manifestHash is null on a fresh agent
        assertFailsWith<SnapshotManifestMismatchException> {
            runBlocking {
                restoreFromSnapshot(
                    agent, snapshot(manifestHash = "DEADBEEF"),
                    allowManifestMismatch = false, resumeWith = null,
                    runtimeContext = AgentRuntimeContext(), messages = mutableListOf(),
                )
            }
        }
    }

    @Test
    fun `restoreFromSnapshot restores messages when the mismatch is allowed`() {
        val agent = agent<String, String>("a") {
            skills { skill<String, String>("s", "d") { implementedBy { it } } }
        }
        val messages = mutableListOf<LlmMessage>()
        runBlocking {
            restoreFromSnapshot(
                agent, snapshot(manifestHash = "X", messages = listOf(LlmMessage("user", "hi"))),
                allowManifestMismatch = true, resumeWith = null,
                runtimeContext = AgentRuntimeContext(), messages = messages,
            )
        }
        assertEquals(1, messages.size)
        assertEquals("hi", messages.first().content)
    }
}
