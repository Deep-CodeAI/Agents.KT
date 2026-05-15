package agents_engine.runtime.events

import agents_engine.core.agent
import agents_engine.model.LlmChunk
import agents_engine.model.LlmMessage
import agents_engine.model.LlmResponse
import agents_engine.model.ModelClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * #1739 — proves that AgentEvent.Token events arrive INCREMENTALLY during
 * the agentic loop, not batched-at-end.
 *
 * The premortem flagged this as the load-bearing claim of streaming. Step
 * 2's tests only checked event *ordering* via `events.toList()`, which
 * buffers everything — a fully-batched implementation would have passed.
 *
 * Approach: a custom ModelClient overrides `chatStream` to emit chunks
 * with deliberate `delay(50)` between them. We collect events with
 * arrival timestamps and assert the first Token's arrival lands well
 * before Completed's. If `chatOrStream` accidentally aggregates and
 * batch-emits, the gap collapses and this test fires.
 *
 * Uses `runBlocking` (real clock) — runTest's virtual time defeats the
 * timing-based assertion this test is built on.
 */
class AgentSessionIncrementalArrivalTest {

    /**
     * Streaming stub: emits four TextDelta chunks with 50ms between each,
     * then End. Total wire-time ≈ 150ms minimum.
     */
    private val incrementalStub = object : ModelClient {
        override fun chat(messages: List<LlmMessage>): LlmResponse =
            error("incrementalStub forces the streaming path; chat() must not be called")

        override suspend fun chatStream(messages: List<LlmMessage>): Flow<LlmChunk> = flow {
            emit(LlmChunk.TextDelta("alpha "))
            delay(50)
            emit(LlmChunk.TextDelta("beta "))
            delay(50)
            emit(LlmChunk.TextDelta("gamma "))
            delay(50)
            emit(LlmChunk.TextDelta("delta"))
            emit(LlmChunk.End(tokenUsage = null))
        }
    }

    @Test
    fun `Token events arrive incrementally while the stream produces chunks, not batched at the end`() = runBlocking {
        val streamingAgent = agent<String, String>("inc") {
            prompt("Incremental stub.")
            model { ollama("llama3"); client = incrementalStub }
            skills {
                skill<String, String>("recite", "Streams four words") { tools() }
            }
        }

        val session = streamingAgent.session("kick")

        val startNs = System.nanoTime()
        var firstTokenMs: Long? = null
        var completedMs: Long? = null
        session.events.collect { event ->
            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
            when (event) {
                is AgentEvent.Token -> if (firstTokenMs == null) firstTokenMs = elapsedMs
                is AgentEvent.Completed<*> -> completedMs = elapsedMs
                else -> {}
            }
        }

        // Both arrival timestamps must have been recorded.
        val first = firstTokenMs ?: error("never observed a Token event")
        val last = completedMs ?: error("never observed a Completed event")

        // Gap >= 100ms means at least two delays elapsed between the first
        // Token arriving and Completed — proves incremental flow. The actual
        // gap should be ~150ms (three delays); 100ms gives slack for CI noise.
        val gap = last - first
        assertTrue(
            gap >= 100,
            "expected first Token to arrive at least 100ms before Completed (proof of incremental flow); " +
                "got first=${first}ms, completed=${last}ms, gap=${gap}ms",
        )

        // Final assembled output spans all four chunks.
        val output = session.await()
        assertIs<String>(output)
        assertTrue("alpha beta gamma delta" in output, "expected full assembled text; got: \"$output\"")
    }
}
