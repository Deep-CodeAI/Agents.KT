package agents_engine.model

import agents_engine.runtime.events.AgentEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #2478 (under Koog regression epic #2474) — partial / OpenRouter-style
 * streaming tool-call chunks must reconstruct into a single coherent
 * `LlmResponse.ToolCalls` keyed by call id, with deltas surfaced as
 * `AgentEvent.ToolCallArgumentsDelta` events for downstream UIs.
 *
 * Koog signal: tool-call deltas where the tool name only appears in the
 * first chunk and later chunks carry only id + argument fragments
 * (OpenRouter's compact streaming form). Agents.KT's
 * [chatOrStream] aggregator handles this by routing every chunk by
 * `callId` — the started/delta/finished events are independent. This
 * test fires that exact shape against the aggregator directly so the
 * contract is locked.
 */
class KoogRegressionStreamingChunkReconstructionTest {

    private fun streamingClient(chunks: List<LlmChunk>): ModelClient = object : ModelClient {
        override fun chat(messages: List<LlmMessage>): LlmResponse =
            error("test uses streaming path only")
        override suspend fun chatStream(messages: List<LlmMessage>): Flow<LlmChunk> = flowOf(*chunks.toTypedArray())
    }

    @Test
    fun `OpenRouter-style chunks — name only in first chunk, args spread across deltas — reconstruct correctly`() {
        // The OpenRouter wire shape: ToolCallStarted with the toolName once,
        // then several ToolCallArgumentsDelta chunks carrying JSON fragments,
        // then ToolCallFinished with the parsed arguments map.
        val chunks = listOf(
            LlmChunk.ToolCallStarted(callId = "call-1", toolName = "search"),
            LlmChunk.ToolCallArgumentsDelta(callId = "call-1", deltaJson = """{"query":"hel"""),
            LlmChunk.ToolCallArgumentsDelta(callId = "call-1", deltaJson = """lo","count":3"""),
            LlmChunk.ToolCallArgumentsDelta(callId = "call-1", deltaJson = """}"""),
            LlmChunk.ToolCallFinished(callId = "call-1", arguments = mapOf("query" to "hello", "count" to 3)),
            LlmChunk.End(tokenUsage = TokenUsage(10, 5, provider = "openrouter", model = "claude")),
        )

        val captured = mutableListOf<AgentEvent<*>>()
        val response = runBlocking {
            chatOrStream(
                client = streamingClient(chunks),
                messages = emptyList(),
                agentId = "a",
                skillName = "s",
                emitter = { captured.add(it) },
            )
        }

        val toolCalls = response as? LlmResponse.ToolCalls
            ?: error("expected ToolCalls response; got $response")
        assertEquals(1, toolCalls.calls.size, "exactly one reconstructed call")
        val call = toolCalls.calls.single()
        assertEquals("call-1", call.callId, "callId must propagate verbatim from the wire")
        assertEquals("search", call.name, "toolName from the single ToolCallStarted chunk")
        assertEquals(mapOf("query" to "hello", "count" to 3), call.arguments)
        assertEquals(TokenUsage(10, 5, provider = "openrouter", model = "claude"), toolCalls.tokenUsage)

        // The aggregator must surface every arg-delta to the consumer event
        // stream so a UI can show streaming JSON. Three delta chunks → three events.
        val argDeltaEvents = captured.filterIsInstance<AgentEvent.ToolCallArgumentsDelta>()
        assertEquals(3, argDeltaEvents.size, "every wire delta becomes one consumer event")
        assertEquals(
            listOf("""{"query":"hel""", """lo","count":3""", """}"""),
            argDeltaEvents.map { it.deltaJson },
            "delta JSON fragments must surface in arrival order, verbatim",
        )
        // Exactly one ToolCallStarted in the consumer stream — not one per delta.
        assertEquals(
            1, captured.filterIsInstance<AgentEvent.ToolCallStarted>().size,
            "ToolCallStarted fires once per call, regardless of how many arg deltas arrive",
        )
    }

    @Test
    fun `interleaved chunks for multiple parallel calls route by callId`() {
        // Two parallel tool calls, their started/delta/finished chunks
        // interleaved on the wire (the harder case — providers like
        // Anthropic SSE do this). Aggregator routes by callId so both
        // calls reconstruct cleanly with their own args.
        val chunks = listOf(
            LlmChunk.ToolCallStarted(callId = "a", toolName = "getWeather"),
            LlmChunk.ToolCallStarted(callId = "b", toolName = "getNews"),
            LlmChunk.ToolCallArgumentsDelta(callId = "a", deltaJson = """{"city":"NYC""}"""),
            LlmChunk.ToolCallArgumentsDelta(callId = "b", deltaJson = """{"topic":"sports"}"""),
            LlmChunk.ToolCallFinished(callId = "b", arguments = mapOf("topic" to "sports")),
            LlmChunk.ToolCallFinished(callId = "a", arguments = mapOf("city" to "NYC")),
            LlmChunk.End(tokenUsage = null),
        )

        val response = runBlocking {
            chatOrStream(
                client = streamingClient(chunks),
                messages = emptyList(),
                agentId = "a",
                skillName = "s",
                emitter = { /* discard */ },
            )
        }

        val calls = (response as LlmResponse.ToolCalls).calls
        assertEquals(2, calls.size)
        // Order preserved from ToolCallStarted arrival, not from ToolCallFinished arrival.
        assertEquals(listOf("a", "b"), calls.map { it.callId })
        val a = calls.first { it.callId == "a" }
        assertEquals("getWeather", a.name)
        assertEquals(mapOf("city" to "NYC"), a.arguments)
        val b = calls.first { it.callId == "b" }
        assertEquals("getNews", b.name)
        assertEquals(mapOf("topic" to "sports"), b.arguments)
    }

    @Test
    fun `args delta with no preceding ToolCallStarted still surfaces as an event (no started fabricated)`() {
        // Defensive: a misbehaving provider emits args deltas without a
        // ToolCallStarted. The aggregator should NOT crash and should NOT
        // fabricate a ToolCallStarted — but the delta still fires as a
        // consumer event so the UI sees the (malformed) wire activity.
        // (Without a corresponding ToolCallFinished, no entry ends up in
        // the reconstructed LlmResponse — silently dropped, which is the
        // safer choice than guessing a tool name.)
        val chunks = listOf(
            LlmChunk.ToolCallArgumentsDelta(callId = "orphan", deltaJson = """{"foo":"bar"}"""),
            LlmChunk.End(tokenUsage = null),
        )

        val captured = mutableListOf<AgentEvent<*>>()
        val response = runBlocking {
            chatOrStream(
                client = streamingClient(chunks),
                messages = emptyList(),
                agentId = "a",
                skillName = "s",
                emitter = { captured.add(it) },
            )
        }

        // No reconstructed tool calls (no Started, no Finished).
        assertTrue(response is LlmResponse.Text, "no Started → no calls in response")
        // The orphan delta did fire as a consumer event though.
        assertEquals(
            1, captured.filterIsInstance<AgentEvent.ToolCallArgumentsDelta>().size,
            "orphan delta still surfaces to the event stream — UI sees the wire",
        )
        assertEquals(
            0, captured.filterIsInstance<AgentEvent.ToolCallStarted>().size,
            "no Started fabricated for an orphan delta",
        )
    }
}
