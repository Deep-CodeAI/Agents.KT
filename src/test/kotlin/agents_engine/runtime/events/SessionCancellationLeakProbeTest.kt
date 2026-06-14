package agents_engine.runtime.events

import agents_engine.core.agent
import agents_engine.model.LlmChunk
import agents_engine.model.LlmMessage
import agents_engine.model.LlmResponse
import agents_engine.model.ModelClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * PROBE — does cancelling the `events` collector actually cancel the underlying agent
 * invocation? `AgentSession`'s docs promise it does ("cancelling the coroutine collecting
 * events cancels the agent invocation"). If the producer runs in a detached scope that the
 * collector can't reach, the invocation keeps making model calls in the background after the
 * consumer walked away — a runaway-work / resource leak AND a broken contract.
 */
class SessionCancellationLeakProbeTest {

    /** A streaming client that parks in `chatStream` until cancelled, recording the cancellation. */
    private class ParkingClient(
        val entered: CompletableDeferred<Unit>,
        val cancelled: CompletableDeferred<Unit>,
    ) : ModelClient {
        override fun chat(messages: List<LlmMessage>): LlmResponse = LlmResponse.Text("never")
        override suspend fun chatStream(messages: List<LlmMessage>): Flow<LlmChunk> = flow {
            entered.complete(Unit)
            try {
                kotlinx.coroutines.awaitCancellation()
            } finally {
                cancelled.complete(Unit)
            }
        }
    }

    @Test
    fun `cancelling the events collector cancels the underlying invocation`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val a = agent<String, String>("parker") {
            model { ollama("stub"); client = ParkingClient(entered, cancelled) }
            skills { skill<String, String>("s", "parks in the model call") { tools() } }
        }

        val session = a.session("go")
        val streaming = CompletableDeferred<Unit>()
        val collector = launch {
            session.events.collect { if (!streaming.isCompleted) streaming.complete(Unit) }
        }

        // Wait until the producer is parked in the model call AND the collector is actively
        // streaming (it has consumed at least one event), so the cancellation lands mid-stream.
        withTimeout(2_000) { entered.await() }
        withTimeout(2_000) { streaming.await() }
        collector.cancel()

        // CONTRACT: cancelling collection must cancel the invocation. If the producer scope is
        // detached, `cancelled` never completes and this times out — exposing the leak.
        withTimeout(2_000) { cancelled.await() }
        assertTrue(cancelled.isCompleted, "the parked model call must observe cancellation")
    }

    @Test
    fun `abandoning the flow after take(1) cancels the invocation`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val a = agent<String, String>("parker2") {
            model { ollama("stub"); client = ParkingClient(entered, cancelled) }
            skills { skill<String, String>("s", "parks") { tools() } }
        }

        val session = a.session("go")
        // take(1) grabs the first event (SkillStarted) then completes collection — which should
        // tear the invocation down, since the consumer is done.
        val collector = launch { session.events.take(1).toList() }
        withTimeout(2_000) { entered.await() }
        collector.join()

        withTimeout(2_000) { cancelled.await() }
        assertTrue(cancelled.isCompleted, "completing collection early must cancel the parked call")
    }

    @Test
    fun `cancelling the await() coroutine cancels the underlying invocation`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val a = agent<String, String>("parker3") {
            model { ollama("stub"); client = ParkingClient(entered, cancelled) }
            skills { skill<String, String>("s", "parks") { tools() } }
        }

        val session = a.session("go")
        // The await-only path (no events collection) must honor the same contract. UNDISPATCHED so
        // the coroutine is genuinely suspended inside await() before we cancel it (a plain launch is
        // lazily dispatched and would be cancelled before its body runs).
        val awaiter = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            runCatching { session.await() }
        }
        withTimeout(2_000) { entered.await() }
        awaiter.cancel()

        withTimeout(2_000) { cancelled.await() }
        assertTrue(cancelled.isCompleted, "cancelling await() must cancel the parked call")
    }
}
