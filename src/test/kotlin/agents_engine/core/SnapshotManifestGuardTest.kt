package agents_engine.core

import agents_engine.model.LlmMessage
import agents_engine.model.LlmResponse
import agents_engine.model.ModelClient
import agents_engine.model.Tool
import agents_engine.model.executeAgentic
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * #2754 — `SessionSnapshot.manifestHash` is enforced on resume. A snapshot
 * taken under one tool/permission set must not silently replay against an
 * agent whose manifest has since changed; that would let resume widen
 * authority by accident. Fail-closed default; opt-in bypass for callers
 * who own a migration story.
 */
class SnapshotManifestGuardTest {

    private fun freshSnapshot(manifestHash: String?) = SessionSnapshot(
        messages = listOf(LlmMessage("user", "go")),
        turns = 0,
        toolCalls = 0,
        toolCallLimit = 8,
        tokensUsed = null,
        memory = emptyMap(),
        requestId = "r-1",
        sessionId = "s-1",
        manifestHash = manifestHash,
    )

    private fun trivialAgent(hash: String?) = agent<String, String>("a") {
        lateinit var noop: Tool<Map<String, Any?>, Any?>
        model { ollama("t"); client = ModelClient { _ -> LlmResponse.Text("done") } }
        tools { noop = tool("noop", "") { _ -> "ok" } }
        skills { skill<String, String>("s", "") { tools(noop) } }
    }.also { it.attachManifestHash(hash) }

    @Test
    fun `resume with matching manifest hash succeeds`() {
        val agent = trivialAgent("hash-A")
        val skill = agent.skills.values.first()
        val result = runBlocking {
            executeAgentic(agent, skill, "go", resumeFrom = freshSnapshot("hash-A"))
        }
        assertEquals("done", result.output)
    }

    @Test
    fun `resume with mismatched manifest hash throws by default`() {
        val agent = trivialAgent("hash-NEW")
        val skill = agent.skills.values.first()
        val ex = assertFailsWith<SnapshotManifestMismatchException> {
            runBlocking { executeAgentic(agent, skill, "go", resumeFrom = freshSnapshot("hash-OLD")) }
        }
        assertEquals("hash-OLD", ex.expected)
        assertEquals("hash-NEW", ex.actual)
    }

    @Test
    fun `resume with mismatch and allowManifestMismatch=true continues`() {
        val agent = trivialAgent("hash-NEW")
        val skill = agent.skills.values.first()
        val result = runBlocking {
            executeAgentic(
                agent, skill, "go",
                resumeFrom = freshSnapshot("hash-OLD"),
                allowManifestMismatch = true,
            )
        }
        assertEquals("done", result.output, "explicit opt-in lets the resume proceed")
    }

    @Test
    fun `resume with null snapshot manifest hash is allowed (legacy back-compat)`() {
        val agent = trivialAgent("hash-NEW")
        val skill = agent.skills.values.first()
        val result = runBlocking {
            executeAgentic(agent, skill, "go", resumeFrom = freshSnapshot(null))
        }
        assertEquals("done", result.output, "pre-0.6.4 snapshots without manifestHash still resume")
    }

    @Test
    fun `resume when agent has no manifest hash and snapshot does still fails closed`() {
        val agent = trivialAgent(null)
        val skill = agent.skills.values.first()
        val ex = assertFailsWith<SnapshotManifestMismatchException> {
            runBlocking { executeAgentic(agent, skill, "go", resumeFrom = freshSnapshot("hash-OLD")) }
        }
        assertEquals("hash-OLD", ex.expected)
        assertEquals(null, ex.actual, "the agent lost its manifest — should still refuse")
    }

    @Test
    fun `exception message names both sides for audit log forensics`() {
        val agent = trivialAgent("currentHash")
        val skill = agent.skills.values.first()
        val ex = assertFailsWith<SnapshotManifestMismatchException> {
            runBlocking { executeAgentic(agent, skill, "go", resumeFrom = freshSnapshot("oldHash")) }
        }
        val msg = assertNotNull(ex.message)
        assertEquals(true, msg.contains("oldHash") && msg.contains("currentHash"), "message=$msg")
    }
}
