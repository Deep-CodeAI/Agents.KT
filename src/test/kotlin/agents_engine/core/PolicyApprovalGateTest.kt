package agents_engine.core

import agents_engine.model.LlmMessage
import agents_engine.model.LlmResponse
import agents_engine.model.ModelClient
import agents_engine.model.Tool
import agents_engine.model.ToolCall
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.assertThrows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * #2490a — `policy { requireHumanApprovalFor(...) }` gate. Pins:
 *
 * 1. When the model issues a gated tool call, the runtime throws
 *    `AgentInterruptException` with an `ApprovalRequest` payload BEFORE
 *    the executor runs.
 * 2. Resume with `HumanDecision.Approved` executes the original tool
 *    with original args.
 * 3. Resume with `HumanDecision.Edited(payload)` executes the tool with
 *    the edited args (payload must be `Map<String, Any?>`).
 * 4. Resume with `HumanDecision.Rejected` skips execution; the model
 *    sees a "rejected by human" tool result.
 * 5. Resume with `HumanDecision.Responded(payload)` skips execution;
 *    the model sees the payload as the tool result.
 * 6. Non-gated tools run normally (gate is opt-in by name).
 * 7. `requireHumanApprovalFor` with an unknown tool name fails fast at
 *    agent construction.
 * 8. `ApprovalRequest.body` carries the call arguments.
 */
class PolicyApprovalGateTest {

    private fun gatedAgent(executorCount: IntArray, executorEffect: (Map<String, Any?>) -> String = { args -> "sent ${args["to"]}" }): Agent<String, String> {
        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.ToolCalls(listOf(ToolCall("send_email", mapOf("to" to "user@example.com"), callId = "call-1"))))
        responses.add(LlmResponse.Text("done"))
        val mock = ModelClient { _ -> responses.removeFirst() }

        return agent<String, String>("Mailer") {
            lateinit var sendEmail: Tool<Map<String, Any?>, Any?>
            model { ollama("t"); client = mock }
            tools {
                sendEmail = tool("send_email", "Send an email") { args ->
                    executorCount[0]++
                    executorEffect(args)
                }
            }
            skills { skill<String, String>("send", "") { tools(sendEmail) } }
            policy { requireHumanApprovalFor("send_email") }
        }
    }

    @Test
    fun `policy gate throws AgentInterruptException with ApprovalRequest before the executor runs`() {
        val executorCount = intArrayOf(0)
        val a = gatedAgent(executorCount)
        val ex = assertThrows<AgentInterruptException> { a("kick off") }
        val req = ex.payload as ApprovalRequest
        assertEquals("Approve tool call: send_email", req.title)
        assertEquals(mapOf("to" to "user@example.com"), req.body, "body carries the call arguments")
        assertEquals(0, executorCount[0], "executor must NOT run before approval")
    }

    @Test
    fun `Approved executes the original tool with original args`() {
        val executorCount = intArrayOf(0)
        val seenArgs = mutableListOf<Map<String, Any?>>()
        val a = gatedAgent(executorCount) { args -> seenArgs += args; "ok" }
        val ex = assertThrows<AgentInterruptException> { a("go") }

        val out = runBlocking {
            a.invokeSuspendResuming(
                input = "go",
                resumeFrom = ex.snapshot,
                resumeWith = HumanDecision.Approved,
            )
        }
        assertEquals("done", out)
        assertEquals(1, executorCount[0], "executor ran exactly once after approval")
        assertEquals(listOf<Map<String, Any?>>(mapOf("to" to "user@example.com")), seenArgs)
    }

    @Test
    fun `Edited executes the tool with the edited args`() {
        val executorCount = intArrayOf(0)
        val seenArgs = mutableListOf<Map<String, Any?>>()
        val a = gatedAgent(executorCount) { args -> seenArgs += args; "ok" }
        val ex = assertThrows<AgentInterruptException> { a("go") }

        val editedArgs: Map<String, Any?> = mapOf("to" to "human-revised@example.com", "cc" to "boss@example.com")
        val out = runBlocking {
            a.invokeSuspendResuming(
                input = "go",
                resumeFrom = ex.snapshot,
                resumeWith = HumanDecision.Edited(payload = editedArgs),
            )
        }
        assertEquals("done", out)
        assertEquals(1, executorCount[0])
        assertEquals(listOf(editedArgs), seenArgs, "executor ran with edited args, not original")
    }

    @Test
    fun `Rejected skips execution and the model sees a rejection tool result`() {
        val executorCount = intArrayOf(0)
        val sawMessages = mutableListOf<List<LlmMessage>>()
        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.ToolCalls(listOf(ToolCall("send_email", mapOf("to" to "user@example.com"), callId = "c1"))))
        responses.add(LlmResponse.Text("done"))
        val mock = ModelClient { msgs -> sawMessages += msgs.toList(); responses.removeFirst() }

        val a = agent<String, String>("a") {
            lateinit var sendEmail: Tool<Map<String, Any?>, Any?>
            model { ollama("t"); client = mock }
            tools { sendEmail = tool("send_email", "Send email") { _ -> executorCount[0]++; "ok" } }
            skills { skill<String, String>("s", "") { tools(sendEmail) } }
            policy { requireHumanApprovalFor("send_email") }
        }

        val ex = assertThrows<AgentInterruptException> { a("go") }
        runBlocking {
            a.invokeSuspendResuming(
                input = "go",
                resumeFrom = ex.snapshot,
                resumeWith = HumanDecision.Rejected,
            )
        }
        assertEquals(0, executorCount[0], "executor never runs on Rejected")
        val resumeMsgs = sawMessages[1]
        val toolResult = resumeMsgs.last { it.role == "tool" }
        assertTrue("rejected" in toolResult.content.lowercase(), "synthesised result conveys rejection: ${toolResult.content}")
    }

    @Test
    fun `Responded skips execution and the model sees the payload as tool result`() {
        val executorCount = intArrayOf(0)
        val sawMessages = mutableListOf<List<LlmMessage>>()
        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.ToolCalls(listOf(ToolCall("send_email", mapOf("to" to "x"), callId = "c1"))))
        responses.add(LlmResponse.Text("done"))
        val mock = ModelClient { msgs -> sawMessages += msgs.toList(); responses.removeFirst() }

        val a = agent<String, String>("a") {
            lateinit var sendEmail: Tool<Map<String, Any?>, Any?>
            model { ollama("t"); client = mock }
            tools { sendEmail = tool("send_email", "") { _ -> executorCount[0]++; "ok" } }
            skills { skill<String, String>("s", "") { tools(sendEmail) } }
            policy { requireHumanApprovalFor("send_email") }
        }

        val ex = assertThrows<AgentInterruptException> { a("go") }
        runBlocking {
            a.invokeSuspendResuming(
                input = "go",
                resumeFrom = ex.snapshot,
                resumeWith = HumanDecision.Responded(payload = "please ask the user to confirm the recipient first"),
            )
        }
        assertEquals(0, executorCount[0])
        val resumeMsgs = sawMessages[1]
        val toolResult = resumeMsgs.last { it.role == "tool" }
        assertTrue(
            "ask the user" in toolResult.content,
            "synthesised result reflects the Responded payload: ${toolResult.content}",
        )
    }

    @Test
    fun `non-gated tool runs without any approval pause`() {
        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.ToolCalls(listOf(ToolCall("read_status", emptyMap()))))
        responses.add(LlmResponse.Text("done"))
        val mock = ModelClient { _ -> responses.removeFirst() }

        val a = agent<String, String>("a") {
            lateinit var sendEmail: Tool<Map<String, Any?>, Any?>
            lateinit var readStatus: Tool<Map<String, Any?>, Any?>
            model { ollama("t"); client = mock }
            tools {
                sendEmail = tool("send_email", "") { _ -> "sent" }
                readStatus = tool("read_status", "") { _ -> "ok" }
            }
            skills { skill<String, String>("s", "") { tools(sendEmail, readStatus) } }
            policy { requireHumanApprovalFor("send_email") }
        }

        // read_status is NOT in the policy — should run without pause.
        val out = a("status?")
        assertEquals("done", out)
    }

    @Test
    fun `requireHumanApprovalFor with unknown tool fails fast at agent construction`() {
        val ex = assertThrows<IllegalArgumentException> {
            agent<String, String>("a") {
                lateinit var sendEmail: Tool<Map<String, Any?>, Any?>
                model { ollama("t") }
                tools { sendEmail = tool("send_email", "") { _ -> "ok" } }
                skills { skill<String, String>("s", "") { tools(sendEmail) } }
                policy { requireHumanApprovalFor("doesNotExist") }
            }
        }
        assertTrue("doesNotExist" in ex.message!!, "error names the offending tool: ${ex.message}")
    }

    @Test
    fun `Edited payload that is not Map fails clearly at resume`() {
        val executorCount = intArrayOf(0)
        val a = gatedAgent(executorCount)
        val ex = assertThrows<AgentInterruptException> { a("go") }
        val resumeError = assertThrows<IllegalStateException> {
            runBlocking {
                a.invokeSuspendResuming(
                    input = "go",
                    resumeFrom = ex.snapshot,
                    resumeWith = HumanDecision.Edited(payload = "not-a-map"),
                )
            }
        }
        assertTrue(
            "Map" in resumeError.message!!,
            "error names the required type: ${resumeError.message}",
        )
    }

    @Test
    fun `resume from a gate snapshot with non-HumanDecision resumeWith fails fast`() {
        val executorCount = intArrayOf(0)
        val a = gatedAgent(executorCount)
        val ex = assertThrows<AgentInterruptException> { a("go") }
        val resumeError = assertThrows<IllegalArgumentException> {
            runBlocking {
                a.invokeSuspendResuming(
                    input = "go",
                    resumeFrom = ex.snapshot,
                    resumeWith = "plain string",  // not a HumanDecision
                )
            }
        }
        assertTrue(
            "HumanDecision" in resumeError.message!!,
            "error names the required type: ${resumeError.message}",
        )
    }

    @Test
    fun `policy gate snapshot survives JSON round-trip (pendingApprovalGate field persists)`() {
        val snap = SessionSnapshot(
            messages = listOf(LlmMessage("user", "go")),
            turns = 0,
            toolCalls = 0,
            toolCallLimit = 8,
            tokensUsed = null,
            memory = emptyMap(),
            requestId = "r-1",
            sessionId = "s-1",
            manifestHash = null,
            pendingInterruptCallId = "call-1",
            pendingApprovalGate = true,
        )
        val encoded = SnapshotJson.encode(snap)
        val decoded = SnapshotJson.decode(encoded)
        assertEquals(true, decoded.pendingApprovalGate)
        assertEquals("call-1", decoded.pendingInterruptCallId)
    }
}
