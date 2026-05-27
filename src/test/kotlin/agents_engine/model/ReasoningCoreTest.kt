package agents_engine.model

import agents_engine.runtime.events.AgentEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #2407 — core reasoning surface. Reasoning streams on its own channel:
 * `LlmChunk.ReasoningDelta` → `AgentEvent.Reasoning`, accumulated into
 * `LlmResponse.reasoning`, separate from the answer `TextDelta`/`Token`.
 * Off by default (no `ReasoningConfig`). Per-vendor parsing is #2408–#2411.
 */
class ReasoningCoreTest {

    private class ScriptedStreamClient(private val chunks: List<LlmChunk>) : ModelClient {
        override fun chat(messages: List<LlmMessage>): LlmResponse = error("not used")
        override suspend fun chatStream(messages: List<LlmMessage>): Flow<LlmChunk> = flow {
            chunks.forEach { emit(it) }
        }
    }

    /** Only implements chat(); relies on the default chatStream passthrough. */
    private class NonStreamingClient(private val response: LlmResponse) : ModelClient {
        override fun chat(messages: List<LlmMessage>): LlmResponse = response
    }

    @Test
    fun `ReasoningDelta becomes AgentEvent_Reasoning and accumulates into LlmResponse_reasoning`() = runTest {
        val client = ScriptedStreamClient(
            listOf(
                LlmChunk.ReasoningDelta("let me think… "),
                LlmChunk.ReasoningDelta("step two"),
                LlmChunk.TextDelta("the answer"),
                LlmChunk.End(null),
            ),
        )
        val events = mutableListOf<AgentEvent<*>>()

        val result = chatOrStream(client, emptyList(), agentId = "a", skillName = "s", emitter = { events += it })

        // Reasoning streamed as its own events, in order, separate from the Token.
        val reasoning = events.filterIsInstance<AgentEvent.Reasoning>().map { it.text }
        assertEquals(listOf("let me think… ", "step two"), reasoning)
        assertEquals(listOf("the answer"), events.filterIsInstance<AgentEvent.Token>().map { it.text })

        // Accumulated onto the response; answer text stays clean.
        val text = assertIs<LlmResponse.Text>(result)
        assertEquals("the answer", text.content)
        assertEquals("let me think… step two", text.reasoning)
    }

    @Test
    fun `default chatStream surfaces reasoning carried on a non-streaming response`() = runTest {
        val client = NonStreamingClient(
            LlmResponse.Text(content = "answer", reasoning = "because X"),
        )
        val events = mutableListOf<AgentEvent<*>>()

        val result = chatOrStream(client, emptyList(), agentId = "a", skillName = "s", emitter = { events += it })

        assertEquals("because X", events.filterIsInstance<AgentEvent.Reasoning>().single().text)
        assertEquals("answer", events.filterIsInstance<AgentEvent.Token>().single().text)
        assertEquals("because X", assertIs<LlmResponse.Text>(result).reasoning)
    }

    @Test
    fun `no reasoning chunks means null reasoning and no Reasoning events`() = runTest {
        val client = ScriptedStreamClient(listOf(LlmChunk.TextDelta("hi"), LlmChunk.End(null)))
        val events = mutableListOf<AgentEvent<*>>()

        val result = chatOrStream(client, emptyList(), agentId = "a", skillName = "s", emitter = { events += it })

        assertTrue(events.none { it is AgentEvent.Reasoning })
        assertNull(assertIs<LlmResponse.Text>(result).reasoning)
    }

    @Test
    fun `reasoning is off by default and opt-in via the model DSL`() {
        val off = ModelBuilder().apply { ollama("m") }.build()
        assertNull(off.reasoning, "reasoning must be off unless explicitly enabled")

        val on = ModelBuilder().apply {
            ollama("m")
            reasoning(budgetTokens = 2048, effort = ReasoningEffort.HIGH)
        }.build()
        val cfg = on.reasoning!!
        assertTrue(cfg.enabled)
        assertEquals(2048, cfg.budgetTokens)
        assertEquals(ReasoningEffort.HIGH, cfg.effort)
    }
}
