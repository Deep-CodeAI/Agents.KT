package agents_engine.runtime.events

import agents_engine.composition.pipeline.session
import agents_engine.composition.pipeline.then
import agents_engine.core.agent
import agents_engine.model.JsonSchema
import agents_engine.model.LlmChunk
import agents_engine.model.LlmMessage
import agents_engine.model.LlmResponse
import agents_engine.model.ModelClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #3866 demo — five counting agents, `a then b then c then d then e`.
 * Each stage's "model" STREAMS its ten numbers as individual wire chunks
 * (`LlmChunk.TextDelta`), so the session flow shows 50 `Token` events
 * arriving live in stage order — while between agents only the complete
 * accumulated string crosses the typed boundary (store-and-forward):
 *
 * ```
 * pipe.session("1")
 *   ▶ a: 1 2 … 10          (10 Token events, agentId=a)
 *   ▶ b: …prefix… 11 … 20  (b re-emits the inherited prefix once, then 10 tokens)
 *   …
 *   ■ Completed(agentId=e) = "1 2 3 … 50"
 * ```
 */
class CountingPipelineStreamingDemoTest {

    /**
     * Streams the continuation of the number sequence found in the prompt:
     * a single number N in the prompt means "start at N" (emit N..N+9, no
     * prefix); a longer sequence means "continue" (echo the inherited
     * sequence once as one chunk, then emit ten new numbers one chunk each).
     */
    private class CountingModelClient : ModelClient {
        private fun plan(messages: List<LlmMessage>): Pair<String, List<Int>> {
            val prompt = messages.last { it.role == "user" }.content
            val numbers = Regex("""\d+""").findAll(prompt).map { it.value.toInt() }.toList()
            return if (numbers.size <= 1) {
                val start = numbers.firstOrNull() ?: 1
                "" to (start until start + 10).toList()
            } else {
                numbers.joinToString(" ") to (numbers.last() + 1..numbers.last() + 10).toList()
            }
        }

        override fun chat(messages: List<LlmMessage>): LlmResponse {
            val (prefix, fresh) = plan(messages)
            val full = (if (prefix.isEmpty()) "" else "$prefix ") + fresh.joinToString(" ")
            return LlmResponse.Text(full)
        }

        override suspend fun chatStream(messages: List<LlmMessage>): Flow<LlmChunk> {
            val (prefix, fresh) = plan(messages)
            return flow {
                // delay(1) per chunk = a real wire: adapters genuinely suspend on
                // every SSE read, which is what lets the session consumer drain
                // concurrently. (A zero-suspension stub overruns the session's
                // 64-event buffer — trySend drops with a logged warning — and
                // yield() is a no-op on the session's Unconfined dispatcher.)
                if (prefix.isNotEmpty()) {
                    kotlinx.coroutines.delay(1)
                    emit(LlmChunk.TextDelta("$prefix "))
                }
                fresh.forEachIndexed { index, n ->
                    kotlinx.coroutines.delay(1)
                    emit(LlmChunk.TextDelta(if (index == fresh.lastIndex) "$n" else "$n "))
                }
                emit(LlmChunk.End(tokenUsage = null))
            }
        }
    }

    private fun countingAgent(name: String) = agent<String, String>(name) {
        model { ollama("counting-stub"); client = CountingModelClient() }
        skills {
            skill<String, String>("count", "Writes the next ten numbers of the sequence") { tools() }
        }
    }

    @Test
    fun `five-stage pipeline streams 50 numbers live in stage order`() = runBlocking {
        val pipe = countingAgent("a") then countingAgent("b") then
            countingAgent("c") then countingAgent("d") then countingAgent("e")

        val session = pipe.session("1")
        val events = session.events.toList()
        val output = session.await()

        // ── The data lane: the final typed value is the full sequence. ──
        assertEquals((1..50).joinToString(" "), output)

        // ── The observation lane: tokens arrived per stage, live. ──
        val tokens = events.filterIsInstance<AgentEvent.Token>()
        // Demo flavor — render the stream the way a UI would see it:
        tokens.groupBy { it.agentId }.forEach { (agentId, stageTokens) ->
            println("▶ $agentId streamed: ${stageTokens.joinToString("") { it.text }.trim()}")
        }

        // Each stage emitted exactly its ten FRESH numbers as individual chunks
        // (b..e also echo the inherited prefix once — that's the store-and-forward
        // boundary made visible: the next agent received a complete value).
        val freshByStage = tokens.groupBy { it.agentId }.mapValues { (_, stageTokens) ->
            stageTokens.map { it.text.trim() }.filter { it.isNotEmpty() && !it.contains(" ") }.map { it.toInt() }
        }
        assertEquals((1..10).toList(), freshByStage["a"], "a wrote 1..10")
        assertEquals((11..20).toList(), freshByStage["b"], "b continued with 11..20")
        assertEquals((21..30).toList(), freshByStage["c"])
        assertEquals((31..40).toList(), freshByStage["d"])
        assertEquals((41..50).toList(), freshByStage["e"])

        // Strict stage ordering on the wire: every a-token precedes every
        // b-token, etc. — sequential composition never interleaves stages.
        val stageOrder = tokens.map { it.agentId }
        assertEquals(
            listOf("a", "b", "c", "d", "e"),
            stageOrder.distinct(),
            "tokens must arrive in chain order; got: $stageOrder",
        )

        // Terminal: exactly one Completed, attributed to the LAST agent.
        val terminal = events.last()
        assertTrue(terminal is AgentEvent.Completed<*> && terminal.agentId == "e", "got: $terminal")
    }
}
