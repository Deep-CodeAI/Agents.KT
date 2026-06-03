package agents_engine.runtime.events

import agents_engine.core.AgentRuntimeContext
import agents_engine.core.withAgentRuntimeContext
import agents_engine.model.AgentEventEmitter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import java.util.logging.Logger

/**
 * `agents_engine/runtime/events/AgentSessionScope.kt` (#2797) — the streaming-session scaffold shared
 * by the composition operators (`branch` / `forum` / `loop` / `parallel` / `pipeline`). Each
 * `operator.session(input)` used to repeat an identical ~25-line block; they now pass only their
 * [terminalAgentId] and run [body].
 *
 * The scaffold owns the whole lifecycle:
 *  - a `Channel.BUFFERED` of typed events surfaced as a cold flow, and a `CompletableDeferred<OUT>`;
 *  - a dedicated `CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)` so a cancelled collector
 *    can't leak the producer coroutine;
 *  - a fresh per-session [AgentRuntimeContext];
 *  - the context-threading [AgentEventEmitter] — the single place the inner `AgentEvent<*>` →
 *    `AgentEvent<OUT>` cast lives (was copy-pasted with `@Suppress` in all five), `trySend` with a
 *    visible drop-log when the consumer lags;
 *  - the terminal `Completed` / `Failed` emission via suspending `send` (terminal events must never be
 *    dropped) and the #2863 cancellation ordering (timeout → bare-cancel re-throw → other failure).
 *
 * [terminalAgentId] is a supplier, not a value, because `Branch` only knows the terminal agent after
 * its source has run (and the `Failed` path must read whatever it had resolved to at the throw point).
 *
 * **Why no `fireAgentEvent` here** (the divergence from `Agent.session`): a composition operator has
 * no single owning agent to attribute a terminal event to, and its inner agents already fire their own
 * events through `runAgentInSession`'s notifying emitter. `Agent.session` keeps its own terminal
 * `fireAgentEvent` (plus `TokenUsage` in the `Completed`) precisely because it *does* own one agent.
 */
internal fun <OUT> agentSessionScope(
    terminalAgentId: () -> String,
    body: suspend (emit: AgentEventEmitter) -> OUT,
): AgentSession<OUT> {
    val channel = Channel<AgentEvent<OUT>>(Channel.BUFFERED)
    val result = CompletableDeferred<OUT>()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    val runtimeContext = AgentRuntimeContext(sessionId = java.util.UUID.randomUUID().toString())

    scope.launch {
        withAgentRuntimeContext(runtimeContext) {
            val emitter: AgentEventEmitter = { event ->
                @Suppress("UNCHECKED_CAST")
                val typed = event.withRuntimeContext(runtimeContext) as AgentEvent<OUT>
                if (channel.trySend(typed).isFailure) {
                    SESSION_SCOPE_LOGGER.warning(
                        "channel.trySend dropped a ${typed::class.simpleName} from a composition session " +
                            "(sessionId='${runtimeContext.sessionId}') — consumer is slower than the operator"
                    )
                }
            }
            try {
                val output = body(emitter)
                channel.send(AgentEvent.Completed(terminalAgentId(), output, null))
                channel.close()
                result.complete(output)
            } catch (timeout: TimeoutCancellationException) {
                // #2863 — caught BEFORE CancellationException (subtype): a timeout / budget breach is a
                // real failure consumers must hear about.
                channel.send(AgentEvent.Failed(terminalAgentId(), timeout))
                channel.close()
                result.completeExceptionally(timeout)
            } catch (cancel: CancellationException) {
                // #2863 — bare cancellation propagates per structured concurrency; no synthetic Failed.
                result.completeExceptionally(cancel)
                channel.close(cancel)
                throw cancel
            } catch (t: Throwable) {
                channel.send(AgentEvent.Failed(terminalAgentId(), t))
                channel.close()
                result.completeExceptionally(t)
            }
        }
    }

    return AgentSession(
        events = channel.consumeAsFlow(),
        resultDeferred = result,
    )
}

// #2806 / #2797 — one logger for visible drops on the non-suspending emitter path, shared by every
// composition session (replaces the per-operator PARALLEL_LOGGER / PIPELINE_LOGGER / BRANCH_LOGGER).
private val SESSION_SCOPE_LOGGER: Logger =
    Logger.getLogger("agents_engine.runtime.events.AgentSessionScope")
