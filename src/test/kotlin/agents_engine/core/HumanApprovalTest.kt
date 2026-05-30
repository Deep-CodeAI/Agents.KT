package agents_engine.core

import agents_engine.generation.Generable
import agents_engine.generation.Guide
import agents_engine.model.LlmMessage
import agents_engine.model.LlmResponse
import agents_engine.model.ModelClient
import agents_engine.model.Tool
import agents_engine.model.ToolCall
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.minutes
import org.junit.jupiter.api.assertThrows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * #2489 — Human approval node, layered on the #2488 interrupt primitive.
 * Pins:
 *
 * 1. `humanApproval { ... }` throws `AgentInterruptException` whose
 *    payload is an `ApprovalRequest`.
 * 2. The four `HumanDecision` variants round-trip through `resumeWith`
 *    — the model sees a JSON-rendered tool result.
 * 3. `PipelineEvent.ApprovalRequested` fires before the throw with the
 *    title + body-presence + timeout fields (no body / PII in the event).
 * 4. `PipelineEvent.ApprovalDecided` fires on resume when `resumeWith`
 *    is a `HumanDecision`.
 * 5. Composes with the manifest-hash restore guard (#2754).
 * 6. Blank title fails fast at the builder.
 */
class HumanApprovalTest {

    private fun approvalAgent(): Agent<String, String> {
        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.ToolCalls(listOf(ToolCall("approve_deploy", mapOf("plan" to "deploy v42")))))
        responses.add(LlmResponse.Text("done"))
        val mock = ModelClient { _ -> responses.removeFirst() }

        return agent<String, String>("Approver") {
            lateinit var approveDeploy: Tool<Map<String, Any?>, Any?>
            model { ollama("t"); client = mock }
            tools {
                approveDeploy = tool("approve_deploy", "Approve deploy") { args ->
                    humanApproval {
                        title = "Deploy to production?"
                        body = args["plan"]
                        timeout = 30.minutes
                        defaultOnTimeout = HumanDecision.Rejected
                    }
                }
            }
            skills { skill<String, String>("s", "") { tools(approveDeploy) } }
        }
    }

    @Test
    fun `humanApproval throws AgentInterruptException whose payload is an ApprovalRequest`() {
        val a = approvalAgent()
        val ex = assertThrows<AgentInterruptException> { a("kick off") }
        val req = ex.payload as ApprovalRequest
        assertEquals("Deploy to production?", req.title)
        assertEquals("deploy v42", req.body)
        assertEquals(30.minutes, req.timeout)
        assertEquals(HumanDecision.Rejected, req.defaultOnTimeout)
    }

    @Test
    fun `HumanDecision Approved round-trips through resumeWith`() {
        val a = approvalAgent()
        val ex = assertThrows<AgentInterruptException> { a("kick off") }
        val out = runBlocking {
            a.invokeSuspendResuming(
                input = "kick off",
                resumeFrom = ex.snapshot,
                resumeWith = HumanDecision.Approved,
            )
        }
        assertEquals("done", out)
    }

    @Test
    fun `HumanDecision Rejected round-trips and the synthesised tool message reflects it`() {
        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.ToolCalls(listOf(ToolCall("approve_deploy", mapOf("plan" to "deploy v42")))))
        responses.add(LlmResponse.Text("done"))
        val sawMessages = mutableListOf<List<LlmMessage>>()
        val mock = ModelClient { msgs -> sawMessages += msgs.toList(); responses.removeFirst() }

        val a = agent<String, String>("a") {
            lateinit var approve: Tool<Map<String, Any?>, Any?>
            model { ollama("t"); client = mock }
            tools {
                approve = tool("approve_deploy", "Approve deploy") { _ ->
                    humanApproval { title = "Deploy?" }
                }
            }
            skills { skill<String, String>("s", "") { tools(approve) } }
        }

        val ex = assertThrows<AgentInterruptException> { a("go") }
        runBlocking {
            a.invokeSuspendResuming(
                input = "go",
                resumeFrom = ex.snapshot,
                resumeWith = HumanDecision.Rejected,
            )
        }

        val resumeMsgs = sawMessages[1]
        val toolResult = resumeMsgs.last { it.role == "tool" }
        // toLlmInput renders sealed object instances via their toString or class name —
        // exact rendering shouldn't matter for the test; what matters is the model sees
        // SOMETHING that conveys "Rejected".
        assertTrue("Rejected" in toolResult.content, "tool message must encode the decision: ${toolResult.content}")
    }

    @Test
    fun `HumanDecision Edited carries a typed payload`() {
        val a = approvalAgent()
        val ex = assertThrows<AgentInterruptException> { a("go") }
        val edited = HumanDecision.Edited(payload = EditedPlan(steps = listOf("staging", "canary 1%", "100%")))

        val out = runBlocking {
            a.invokeSuspendResuming(
                input = "go",
                resumeFrom = ex.snapshot,
                resumeWith = edited,
            )
        }
        assertEquals("done", out)
    }

    @Test
    fun `HumanDecision Responded carries a free-form payload`() {
        val a = approvalAgent()
        val ex = assertThrows<AgentInterruptException> { a("go") }

        val out = runBlocking {
            a.invokeSuspendResuming(
                input = "go",
                resumeFrom = ex.snapshot,
                resumeWith = HumanDecision.Responded(payload = "ask the user about rollback strategy first"),
            )
        }
        assertEquals("done", out)
    }

    @Test
    fun `ApprovalRequested PipelineEvent fires with field-only audit row (no body)`() {
        val a = approvalAgent()
        val events = mutableListOf<PipelineEvent>()
        a.observe { events += it }

        assertThrows<AgentInterruptException> { a("go") }

        val req = events.filterIsInstance<PipelineEvent.ApprovalRequested>().single()
        assertEquals("Deploy to production?", req.title)
        assertTrue(req.hasBody, "body was attached → hasBody true")
        assertEquals(30.minutes.inWholeMilliseconds, req.timeoutMs)
        // The audit row should NOT carry the body itself — only that one was present.
        assertNotNull(req.runtimeContext.requestId)
    }

    @Test
    fun `ApprovalDecided fires when resume passes a HumanDecision`() {
        val a = approvalAgent()
        val events = mutableListOf<PipelineEvent>()
        a.observe { events += it }

        val ex = assertThrows<AgentInterruptException> { a("go") }
        runBlocking {
            a.invokeSuspendResuming(
                input = "go",
                resumeFrom = ex.snapshot,
                resumeWith = HumanDecision.Edited(payload = "modified plan"),
            )
        }

        val decided = events.filterIsInstance<PipelineEvent.ApprovalDecided>().single()
        assertEquals("Edited", decided.decision)
        assertTrue(decided.hasPayload)
    }

    @Test
    fun `ApprovalDecided does NOT fire when resume passes a non-HumanDecision value (raw interrupt path)`() {
        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.ToolCalls(listOf(ToolCall("ask", mapOf("q" to "q")))))
        responses.add(LlmResponse.Text("done"))
        val mock = ModelClient { _ -> responses.removeFirst() }

        val a = agent<String, String>("a") {
            lateinit var ask: Tool<Map<String, Any?>, Any?>
            model { ollama("t"); client = mock }
            // Plain interrupt(), not humanApproval — payload is just a string.
            tools { ask = tool("ask", "") { _ -> interrupt(payload = "what?") } }
            skills { skill<String, String>("s", "") { tools(ask) } }
        }
        val events = mutableListOf<PipelineEvent>()
        a.observe { events += it }

        val ex = assertThrows<AgentInterruptException> { a("go") }
        runBlocking {
            a.invokeSuspendResuming("go", resumeFrom = ex.snapshot, resumeWith = "plain string reply")
        }

        assertTrue(
            events.none { it is PipelineEvent.ApprovalDecided },
            "ApprovalDecided is gated on resumeWith being a HumanDecision",
        )
        // Also no ApprovalRequested for a non-humanApproval interrupt
        assertTrue(events.none { it is PipelineEvent.ApprovalRequested })
    }

    @Test
    fun `humanApproval composes with manifest-hash restore guard`() {
        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.ToolCalls(listOf(ToolCall("approve", emptyMap()))))
        val mock = ModelClient { _ -> responses.removeFirst() }

        val a = agent<String, String>("ManifestedApprover") {
            lateinit var approve: Tool<Map<String, Any?>, Any?>
            model { ollama("t"); client = mock }
            tools { approve = tool("approve", "") { _ -> humanApproval { title = "?" } } }
            skills { skill<String, String>("s", "") { tools(approve) } }
        }.also { it.attachManifestHash("hash-OLD") }

        val ex = assertThrows<AgentInterruptException> { a("go") }

        val newAgent = agent<String, String>("ManifestedApprover") {
            lateinit var approve: Tool<Map<String, Any?>, Any?>
            model { ollama("t"); client = mock }
            tools { approve = tool("approve", "") { _ -> humanApproval { title = "?" } } }
            skills { skill<String, String>("s", "") { tools(approve) } }
        }.also { it.attachManifestHash("hash-NEW") }

        assertThrows<SnapshotManifestMismatchException> {
            runBlocking {
                newAgent.invokeSuspendResuming(
                    input = "go",
                    resumeFrom = ex.snapshot,
                    resumeWith = HumanDecision.Approved,
                )
            }
        }
    }

    @Test
    fun `humanApproval with blank title fails fast at the builder`() {
        // We can't reach humanApproval { title = "" } from inside a real tool
        // executor because the builder throws BEFORE interrupt — so the build
        // call itself surfaces the error to whoever calls it (here, directly).
        val ex = assertThrows<IllegalArgumentException> {
            ApprovalBuilder().apply { title = "" }.build()
        }
        assertTrue("title" in ex.message!!.lowercase(), "error names the missing field: ${ex.message}")
    }

    @Generable("A modified deploy plan from the human reviewer.")
    data class EditedPlan(
        @Guide("Ordered list of deploy steps")
        val steps: List<String>,
    )
}
