package agents_engine.runtime.events

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
) {
    /**
     * Awaits the agent's typed output. Throws the original exception (NOT
     * wrapped) if the invocation failed — the [AgentEvent.Failed] event
     * still appears in [events] as the terminal element.
     */
    suspend fun await(): OUT = resultDeferred.await()
}
