package agents_engine.core

import agents_engine.generation.Generable
import agents_engine.generation.Guide
import agents_engine.model.LlmMessage
import agents_engine.model.LlmResponse
import agents_engine.model.ModelClient
import agents_engine.model.ToolCall
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// #4493 — typed hook payloads: reified onToolCall/onToolResult decode the
// tool's @Generable Args; other tools are ignored; bad payloads skip
// silently; existing untyped listeners keep firing (chained, not replaced).

class TypedToolHooksTest {

    @Generable
    data class ChargeArgs(
        @Guide("Amount in cents") val amount: Int,
        @Guide("Currency code") val currency: String,
    )

    private fun chargeAgent(vararg responses: LlmResponse): Agent<String, String> {
        val queue = ArrayDeque(responses.toList())
        val mock = object : ModelClient {
            override fun chat(messages: List<LlmMessage>): LlmResponse = queue.removeFirst()
        }
        return agent<String, String>("checkout") {
            model { ollama("stub"); client = mock }
            tools {
                tool("charge_card") { executor { _ -> "charged" } }
                tool("log") { executor { _ -> "logged" } }
            }
            skills {
                skill<String, String>("buy", "Buys") {
                    @Suppress("DEPRECATION")
                    tools("charge_card", "log")
                }
            }
        }
    }

    @Test
    fun `onToolCall decodes typed args pre-execution for the named tool only`() {
        val seen = mutableListOf<ChargeArgs>()
        val a = chargeAgent(
            LlmResponse.ToolCalls(listOf(ToolCall("log", mapOf("note" to "hi")))),
            LlmResponse.ToolCalls(listOf(ToolCall("charge_card", mapOf("amount" to 4200, "currency" to "EUR")))),
            LlmResponse.Text("done"),
        )
        a.onToolCall<ChargeArgs>("charge_card") { args -> seen.add(args) }

        assertEquals("done", a("go"))
        assertEquals(listOf(ChargeArgs(4200, "EUR")), seen, "only charge_card decodes, typed")
    }

    @Test
    fun `onToolResult delivers typed args plus the executor result and chains the untyped listener`() {
        val typed = mutableListOf<Pair<ChargeArgs, Any?>>()
        val untyped = mutableListOf<String>()
        val a = chargeAgent(
            LlmResponse.ToolCalls(listOf(ToolCall("charge_card", mapOf("amount" to 100, "currency" to "USD")))),
            LlmResponse.Text("done"),
        )
        a.onToolUse { name, _, _ -> untyped.add(name) }
        a.onToolResult<ChargeArgs>("charge_card") { args, result -> typed.add(args to result) }

        assertEquals("done", a("go"))
        assertEquals(listOf<Pair<ChargeArgs, Any?>>(ChargeArgs(100, "USD") to "charged"), typed)
        assertEquals(listOf("charge_card"), untyped, "prior untyped listener must keep firing")
    }

    @Test
    fun `undecodable payloads are skipped silently — hooks never kill the run`() {
        val seen = mutableListOf<ChargeArgs>()
        val a = chargeAgent(
            LlmResponse.ToolCalls(listOf(ToolCall("charge_card", mapOf("garbage" to true)))),
            LlmResponse.Text("done"),
        )
        a.onToolCall<ChargeArgs>("charge_card") { args -> seen.add(args) }

        assertEquals("done", a("go"))
        assertTrue(seen.isEmpty(), "non-decoding payloads skip; got: $seen")
    }
}
