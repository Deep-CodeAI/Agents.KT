package agents_engine.runtime.events

import agents_engine.composition.pipeline.session
import agents_engine.composition.pipeline.then
import agents_engine.core.agent
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
 * #4496 — drop accounting on streaming sessions. The inner emitter forwards via `trySend` into a
 * 64-slot buffer; a zero-suspension producer on the Unconfined session dispatcher fills it before
 * the collector gets a single turn, so everything past slot 64 drops. Pre-#4496 each loss was one
 * WARNING and otherwise invisible to code; now the session counts them ([AgentSession.droppedEvents])
 * and logs one summary at close. The typed RESULT lane is unaffected either way — only observation
 * events drop, never the output or the terminal Completed/Failed.
 */
class SessionDropAccountingTest {

    /** Streams [chunks] text deltas with zero suspension — deliberately overruns the 64 buffer. */
    private class FloodingModelClient(private val chunks: Int) : ModelClient {
        override fun chat(messages: List<LlmMessage>): LlmResponse =
            LlmResponse.Text(List(chunks) { "x" }.joinToString(""))

        override suspend fun chatStream(messages: List<LlmMessage>): Flow<LlmChunk> = flow {
            repeat(chunks) { emit(LlmChunk.TextDelta("x")) }
            emit(LlmChunk.End(tokenUsage = null))
        }
    }

    private fun flooder(name: String, chunks: Int) = agent<String, String>(name) {
        model { ollama("flood-stub"); client = FloodingModelClient(chunks) }
        skills { skill<String, String>("s", "floods tokens") { tools() } }
    }

    @Test
    fun `a zero-suspension producer overruns the buffer — droppedEvents counts the loss`() = runBlocking {
        val session = flooder("noisy", chunks = 500).session("go")
        val events = session.events.toList()
        val output = session.await()

        // The RESULT lane never degrades: full output, terminal Completed delivered.
        assertEquals(500, output.length)
        assertTrue(events.last() is AgentEvent.Completed<*>, "terminal must survive; got: ${events.last()}")

        // The observation lane lost events past the buffer — and the session says so, precisely.
        assertTrue(session.droppedEvents > 0, "500 unsuspended chunks must overrun the 64 buffer")
        val tokens = events.filterIsInstance<AgentEvent.Token>()
        assertTrue(tokens.size < 500, "got ${tokens.size} tokens — expected drops")
    }

    @Test
    fun `a paced session drops nothing and reports zero`() = runBlocking {
        val a = agent<String, String>("calm") {
            skills { skill<String, String>("echo", "echoes") { implementedBy { it.uppercase() } } }
        }
        val session = a.session("hi")
        session.events.toList()
        assertEquals("HI", session.await())
        assertEquals(0L, session.droppedEvents)
    }

    @Test
    fun `composition sessions account drops too`() = runBlocking {
        val pipe = flooder("p1", chunks = 200) then flooder("p2", chunks = 200)
        val session = pipe.session("go")
        val events = session.events.toList()
        session.await()

        assertTrue(session.droppedEvents > 0, "two flooding stages must overrun the composition buffer")
        assertTrue(events.last() is AgentEvent.Completed<*>)
    }
}
