package agents_engine.core

import agents_engine.generation.Generable
import agents_engine.generation.Guide
import agents_engine.model.LlmMessage
import agents_engine.model.LlmResponse
import agents_engine.model.ModelClient
import agents_engine.model.Tool
import agents_engine.model.ToolCall
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * #2488 — HITL interrupt/resume primitive. Composes with the snapshot
 * machinery from #2416 (foundation), #2754 (manifest-hash restore guard),
 * #2755 (namespaced memory restore), and the public seam from #2749
 * (`Agent.invokeSuspendResuming`).
 *
 * Acceptance criteria from the upstream ticket:
 * 1. `interrupt(payload)` from inside a tool executor surfaces an
 *    [AgentInterruptException] carrying the snapshot + payload.
 * 2. Resume with `resumeWith = reply` continues the loop deterministically,
 *    with the model seeing the synthesised tool result.
 * 3. Persistence round-trip: interrupt → save → load → resume produces an
 *    identical continuation.
 * 4. Manifest-hash restore guard refuses to resume across a manifest change.
 * 5. The composition operators (e.g. Pipeline) propagate the exception
 *    unchanged.
 */
class InterruptResumeTest {

    @Test
    fun `interrupt from inside a tool surfaces AgentInterruptException with payload + snapshot`() {
        // Model emits one tool call (ask_human). The tool calls interrupt(...).
        val mock = ModelClient { _ ->
            LlmResponse.ToolCalls(listOf(ToolCall("ask_human", mapOf("q" to "which file"))))
        }

        val a = agent<String, String>("Asker") {
            lateinit var askHuman: Tool<Map<String, Any?>, Any?>
            model { ollama("test"); client = mock }
            tools {
                askHuman = tool("ask_human", "Ask the user") { args ->
                    interrupt(payload = args["q"])
                }
            }
            skills { skill<String, String>("ask", "ask") { tools(askHuman) } }
        }

        val ex = assertThrows<AgentInterruptException> { a("kick off") }
        assertEquals("which file", ex.payload, "payload surfaces verbatim")
        assertNotNull(ex.snapshot, "snapshot is carried on the exception")
        assertNotNull(ex.snapshot.pendingInterruptCallId, "snapshot marks the pending call")
        assertEquals(ex.snapshot.pendingInterruptCallId, ex.pendingToolCallId, "exception + snapshot agree on call id")
    }

    @Test
    fun `resume with resumeWith synthesises the tool result and continues to completion`() {
        // Two-turn agent: ask_human → resume → text answer.
        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.ToolCalls(listOf(ToolCall("ask_human", mapOf("q" to "which?")))))
        responses.add(LlmResponse.Text("you said: src/main.kt"))
        val mock = ModelClient { _ -> responses.removeFirst() }

        val a = agent<String, String>("Asker") {
            lateinit var askHuman: Tool<Map<String, Any?>, Any?>
            model { ollama("test"); client = mock }
            tools {
                askHuman = tool("ask_human", "Ask the user") { args ->
                    interrupt(payload = args["q"])
                }
            }
            skills { skill<String, String>("ask", "ask") { tools(askHuman) } }
        }

        val ex = assertThrows<AgentInterruptException> { a("kick off") }
        val out = runBlocking {
            a.invokeSuspendResuming(
                input = "kick off",
                resumeFrom = ex.snapshot,
                resumeWith = "src/main.kt",
            )
        }
        assertEquals("you said: src/main.kt", out)
    }

    @Test
    fun `the synthesised tool message carries the resumeWith content (toLlmInput-rendered)`() {
        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.ToolCalls(listOf(ToolCall("ask", mapOf("q" to "q")))))
        responses.add(LlmResponse.Text("done"))
        val sawMessages = mutableListOf<List<LlmMessage>>()
        val mock = ModelClient { msgs -> sawMessages += msgs.toList(); responses.removeFirst() }

        val a = agent<String, String>("a") {
            lateinit var ask: Tool<Map<String, Any?>, Any?>
            model { ollama("t"); client = mock }
            tools { ask = tool("ask", "") { _ -> interrupt(payload = "q") } }
            skills { skill<String, String>("s", "") { tools(ask) } }
        }

        val ex = assertThrows<AgentInterruptException> { a("go") }
        runBlocking { a.invokeSuspendResuming("go", resumeFrom = ex.snapshot, resumeWith = "reply-text") }

        // The second model call saw the synthesised tool result message.
        val resumeMsgs = sawMessages[1]
        val toolResult = resumeMsgs.last { it.role == "tool" }
        assertEquals("reply-text", toolResult.content, "synthesised tool result == toLlmInput(resumeWith)")
    }

    @Test
    fun `resume without resumeWith when interrupt is pending throws IllegalArgumentException`() {
        val mock = ModelClient { _ ->
            LlmResponse.ToolCalls(listOf(ToolCall("ask", mapOf("q" to "q"))))
        }
        val a = agent<String, String>("a") {
            lateinit var ask: Tool<Map<String, Any?>, Any?>
            model { ollama("t"); client = mock }
            tools { ask = tool("ask", "") { _ -> interrupt(payload = "q") } }
            skills { skill<String, String>("s", "") { tools(ask) } }
        }

        val ex = assertThrows<AgentInterruptException> { a("go") }
        val resumeError = assertThrows<IllegalArgumentException> {
            runBlocking { a.invokeSuspendResuming("go", resumeFrom = ex.snapshot, resumeWith = null) }
        }
        assertTrue("pendingInterruptCallId" in resumeError.message!!, "error explains the contract: ${resumeError.message}")
    }

    @Test
    fun `onTurnCheckpoint fires with the interrupt snapshot before AgentInterruptException is thrown`() {
        val mock = ModelClient { _ ->
            LlmResponse.ToolCalls(listOf(ToolCall("ask", mapOf("q" to "q"))))
        }
        val captured = mutableListOf<SessionSnapshot>()

        val a = agent<String, String>("a") {
            lateinit var ask: Tool<Map<String, Any?>, Any?>
            model { ollama("t"); client = mock }
            tools { ask = tool("ask", "") { _ -> interrupt(payload = "q") } }
            skills { skill<String, String>("s", "") { tools(ask) } }
        }

        val ex = assertThrows<AgentInterruptException> {
            runBlocking { a.invokeSuspendResuming("go", onTurnCheckpoint = { captured += it }) }
        }
        assertEquals(1, captured.size, "checkpoint fires once with the interrupt snapshot")
        assertEquals(ex.snapshot, captured.single(), "checkpoint snapshot == exception.snapshot")
    }

    @Test
    fun `persistence round-trip - save to FileSnapshotStore across simulated process restart`(@TempDir tmp: Path) {
        // "Restart" simulated by encoding/decoding via FileSnapshotStore and then
        // building a brand-new agent instance to handle the resume.
        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.ToolCalls(listOf(ToolCall("ask", mapOf("q" to "q")))))
        val mock = ModelClient { _ -> responses.removeFirst() }

        val firstAgent = agent<String, String>("Persistent") {
            lateinit var ask: Tool<Map<String, Any?>, Any?>
            model { ollama("t"); client = mock }
            tools { ask = tool("ask", "") { _ -> interrupt(payload = "q") } }
            skills { skill<String, String>("s", "") { tools(ask) } }
        }

        val ex = assertThrows<AgentInterruptException> { firstAgent("go") }
        val store = FileSnapshotStore(tmp)
        store.save("session-42", ex.snapshot)

        // "Process restart" — build a fresh agent, fresh model, load snapshot.
        val resumeResponses = ArrayDeque<LlmResponse>().apply { add(LlmResponse.Text("done")) }
        val resumeMock = ModelClient { _ -> resumeResponses.removeFirst() }
        val resumingAgent = agent<String, String>("Persistent") {
            lateinit var ask: Tool<Map<String, Any?>, Any?>
            model { ollama("t"); client = resumeMock }
            tools { ask = tool("ask", "") { _ -> interrupt(payload = "q") } }
            skills { skill<String, String>("s", "") { tools(ask) } }
        }

        val loaded = assertNotNull(store.load("session-42"))
        assertEquals(ex.snapshot.pendingInterruptCallId, loaded.pendingInterruptCallId, "callId survives JSON round-trip")

        val out = runBlocking {
            resumingAgent.invokeSuspendResuming(
                input = "go",
                resumeFrom = loaded,
                resumeWith = "reply-after-restart",
            )
        }
        assertEquals("done", out, "fresh agent resumes the interrupted session end-to-end")
    }

    @Test
    fun `manifest-hash mismatch on resume of an interrupt snapshot fails closed (composes with #2754)`() {
        val mock = ModelClient { _ ->
            LlmResponse.ToolCalls(listOf(ToolCall("ask", mapOf("q" to "q"))))
        }
        val originalAgent = agent<String, String>("Manifested") {
            lateinit var ask: Tool<Map<String, Any?>, Any?>
            model { ollama("t"); client = mock }
            tools { ask = tool("ask", "") { _ -> interrupt(payload = "q") } }
            skills { skill<String, String>("s", "") { tools(ask) } }
        }.also { it.attachManifestHash("hash-OLD") }

        val ex = assertThrows<AgentInterruptException> { originalAgent("go") }

        // Manifest changed (agent rebuilt with different hash).
        val newAgent = agent<String, String>("Manifested") {
            lateinit var ask: Tool<Map<String, Any?>, Any?>
            model { ollama("t"); client = mock }
            tools { ask = tool("ask", "") { _ -> interrupt(payload = "q") } }
            skills { skill<String, String>("s", "") { tools(ask) } }
        }.also { it.attachManifestHash("hash-NEW") }

        assertThrows<SnapshotManifestMismatchException> {
            runBlocking {
                newAgent.invokeSuspendResuming(
                    input = "go",
                    resumeFrom = ex.snapshot,
                    resumeWith = "reply",
                )
            }
        }
    }

    @Test
    fun `typed @Generable payload survives the round-trip on the exception`() {
        val mock = ModelClient { _ ->
            LlmResponse.ToolCalls(listOf(ToolCall("ask", mapOf("q" to "q"))))
        }
        val a = agent<String, String>("a") {
            lateinit var ask: Tool<Map<String, Any?>, Any?>
            model { ollama("t"); client = mock }
            tools { ask = tool("ask", "") { _ -> interrupt(payload = HumanQuestion("Which file?", urgency = "high")) } }
            skills { skill<String, String>("s", "") { tools(ask) } }
        }

        val ex = assertThrows<AgentInterruptException> { a("go") }
        val payload = ex.payload as HumanQuestion
        assertEquals("Which file?", payload.text)
        assertEquals("high", payload.urgency)
    }

    @Generable("A question to render for the human operator.")
    data class HumanQuestion(
        @Guide("The question text") val text: String,
        @Guide("low | medium | high") val urgency: String,
    )
}
