package agents_engine.core

import agents_engine.model.LlmResponse
import agents_engine.model.ModelClient
import agents_engine.model.Tool
import agents_engine.model.ToolCall
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

// #3868 — humanGate: the discoverable HITL adapter over interrupt/resume.

class HumanGateRegistryTest {

    private fun gatedAgent(): Agent<String, String> {
        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.ToolCalls(listOf(ToolCall("charge_card", mapOf("amount" to "5000")))))
        responses.add(LlmResponse.Text("charged after approval"))
        val mock = ModelClient { _ -> responses.removeFirst() }
        return agent<String, String>("checkout") {
            lateinit var charge: Tool<Map<String, Any?>, Any?>
            model { ollama("test"); client = mock }
            tools {
                charge = tool("charge_card", "Charges the card") { args ->
                    humanApproval {
                        title = "Charge of ${args["amount"]} requires approval"
                        body = args
                    }
                }
            }
            skills { skill<String, String>("buy", "Buys") { tools(charge) } }
        }
    }

    @Test
    fun `guard completes directly when nothing interrupts`() {
        val plain = agent<String, String>("plain") {
            skills { skill<String, String>("echo", "Echo") { implementedBy { "ok: $it" } } }
        }
        val outcome = HumanGateRegistry().guard(plain, "x")
        assertEquals("ok: x", assertIs<GateOutcome.Completed<String>>(outcome).output)
    }

    @Test
    fun `guard parks an approval-interrupted run as a pending gate and approve resumes it`() {
        val registry = HumanGateRegistry()
        val outcome = registry.guard(gatedAgent(), "order-1")

        val gate = assertIs<GateOutcome.Paused<String>>(outcome).gate
        assertTrue(gate.reason.startsWith("Charge of 5000"), "reason carries the approval title; got: ${gate.reason}")
        assertEquals(listOf(gate.gateId), registry.pending().map { it.gateId })
        assertEquals(gate, registry.find(gate.gateId))

        val output = gate.approve(reviewer = "alice@acme.com", comment = "verified")
        assertEquals("charged after approval", output)
        assertTrue(registry.pending().isEmpty(), "resolved gates leave the registry")
    }

    @Test
    fun `reject resumes with the rejection as the tool result`() {
        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.ToolCalls(listOf(ToolCall("charge_card", emptyMap()))))
        responses.add(LlmResponse.Text("not charged — rejected"))
        val mock = ModelClient { _ -> responses.removeFirst() }
        val a = agent<String, String>("checkout") {
            lateinit var charge: Tool<Map<String, Any?>, Any?>
            model { ollama("test"); client = mock }
            tools { charge = tool("charge_card", "Charges") { _ -> humanApproval { title = "Approve?" } } }
            skills { skill<String, String>("buy", "Buys") { tools(charge) } }
        }
        val registry = HumanGateRegistry()
        val gate = assertIs<GateOutcome.Paused<String>>(registry.guard(a, "order")).gate

        assertEquals("not charged — rejected", gate.reject(reviewer = "bob", comment = "fraud check failed"))
    }

    @Test
    fun `a gate resolves exactly once`() {
        val registry = HumanGateRegistry()
        val gate = assertIs<GateOutcome.Paused<String>>(registry.guard(gatedAgent(), "order")).gate
        gate.approve(reviewer = "alice")
        assertFailsWith<IllegalStateException> { gate.approve(reviewer = "mallory") }
    }
}
