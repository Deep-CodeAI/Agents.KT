package agents_engine.runtime.events

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.Flow

/**
 * `agents_engine/runtime/events/AgentSession.kt` — the [AgentSession]
 * handle returned by `agent.session(input)` (#1736). Carries a cold
 * `Flow<AgentEvent<OUT>>` and a suspending `await(): OUT`. Cold flow —
 * each `session(...)` call is a fresh invocation. Cancellation
 * propagates: cancelling collection of `events` or the `await()`
 * coroutine cancels the upstream agent invocation (and, in step 3,
 * the LLM HTTP call). See
 * `src/main/resources/internals-agent/runtime/events/AgentSession.md`
 * (#1837 / #1893).
 */

/**
 * #1736 — handle to an in-flight agent invocation. Returned by
 * `agent.session(input)`. Carries a cold [events] flow and a terminal
 * [await] entry point.
 *
 * Cold flow: each call to `agent.session(...)` starts a fresh invocation,
 * regardless of whether you've collected from a previous session's [events].
 * To share one invocation's events across multiple collectors, use
 * `events.shareIn(scope, ...)`.
 *
 * Cancellation: cancelling the coroutine collecting [events] cancels the
 * agent invocation. Cancelling the coroutine calling [await] does the same.
 * Either path propagates into the upstream LLM HTTP call (step 3 hardens
 * this once native streaming adapters land).
 */
class AgentSession<OUT> internal constructor(
    val events: Flow<AgentEvent<OUT>>,
    private val resultDeferred: Deferred<OUT>,
    private val dropCounter: SessionDropCounter? = null,
    /**
     * #4499 — tears down the detached producer scope. The [events] flow already cancels it via
     * `onCompletion`; [await] invokes it on cancellation so the await-only path honors the same
     * "cancel me → cancel the invocation" contract. No-op default for the legacy constructors.
     */
    private val cancelProducer: () -> Unit = {},
) {
    /**
     * Awaits the agent's typed output. Throws the original exception (NOT
     * wrapped) if the invocation failed — the [AgentEvent.Failed] event
     * still appears in [events] as the terminal element.
     *
     * #4499 — cancelling the awaiting coroutine cancels the underlying invocation (matching the
     * [events]-collection contract): the producer scope is torn down before the cancellation
     * propagates on.
     */
    suspend fun await(): OUT = try {
        resultDeferred.await()
    } catch (cancel: CancellationException) {
        cancelProducer()
        throw cancel
    }

    /**
     * #4496 — count of inner events dropped so far because the consumer lagged behind the
     * producer (the non-suspending emitter forwards via `trySend` into a 64-slot buffer).
     * Live and monotonic; `0` when nothing was lost. Terminal `Completed` / `Failed` events
     * use suspending `send` and never drop. A non-zero value after collection means the
     * event stream has gaps — assert on this in tests instead of scraping warning logs.
     */
    val droppedEvents: Long get() = dropCounter?.droppedEvents ?: 0L
}
