package agents_engine.composition.branch

import agents_engine.core.AgentRuntimeContext
import agents_engine.core.withAgentRuntimeContext
import agents_engine.model.AgentEventEmitter
import agents_engine.runtime.events.AgentEvent
import agents_engine.runtime.events.AgentSession
import agents_engine.runtime.events.runAgentInSession
import agents_engine.runtime.events.withRuntimeContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import java.util.logging.Logger

/**
 * `agents_engine/composition/branch/BranchSessionExtension.kt` — adds the
 * `branch.session(input)` extension. Source agent runs first, streaming
 * events with `agentId=source.name`. The matched route's agent (or
 * pipeline) then runs, streaming its own events. Terminal `Completed`
 * carries the routed agent's name as `agentId`. Routes built outside
 * `BranchBuilder` lack `sessionExecutor` → fall back to non-streaming
 * `executor` but still surface terminal `Completed`/`Failed` (#1748).
 * See `src/main/resources/internals-agent/composition/branch/BranchSessionExtension.md`
 * (#1837 / #1866).
 */

/**
 * #1748 — start a streaming session against [this] branch.
 *
 * The source agent runs first to produce the routing value; its events
 * stream with `agentId=source.name`. The matched route's agent (or
 * pipeline) then runs; its events stream with their own `agentId`s.
 * Terminal `Completed` carries the routed agent's name as `agentId` —
 * that's the agent whose output is the Branch's output.
 *
 * Failure handling: if either the source or the routed agent throws,
 * the terminal event is `Failed` with the original cause; `await()`
 * rethrows. Routes constructed outside `BranchBuilder` (no
 * `sessionExecutor`) fall back to the regular executor — events from
 * the routed agent won't stream, but the route still executes and
 * the terminal `Completed`/`Failed` fires correctly.
 */
fun <IN, OUT> Branch<IN, OUT>.session(input: IN): AgentSession<OUT> {
    val branch = this
    val channel = Channel<AgentEvent<OUT>>(Channel.BUFFERED)
    val result = CompletableDeferred<OUT>()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    val runtimeContext = AgentRuntimeContext(sessionId = java.util.UUID.randomUUID().toString())

    scope.launch {
        withAgentRuntimeContext(runtimeContext) {
            @Suppress("UNCHECKED_CAST")
            val emitter: AgentEventEmitter = { event ->
                val typed = event.withRuntimeContext(runtimeContext) as AgentEvent<OUT>
                val sendResult = channel.trySend(typed)
                if (sendResult.isFailure) {
                    BRANCH_LOGGER.warning(
                        "channel.trySend dropped a ${typed::class.simpleName} from branch session " +
                            "(sessionId='${runtimeContext.sessionId}')"
                    )
                }
            }
            var terminalAgentId = branch.source.name
            try {
                // Source agent streams first.
                @Suppress("UNCHECKED_CAST")
                val sourcePair = runAgentInSession(
                    branch.source as agents_engine.core.Agent<IN, Any?>,
                    input,
                    emitter,
                )
                val sourceOut = sourcePair.first

                // Pick the matching route and run it.
                val route = branch.matchRoute(sourceOut)
                    ?: error(
                        "No branch route matched for ${sourceOut?.let { it::class.simpleName } ?: "null"} " +
                            "and no onElse clause was declared."
                    )
                // Terminal Completed gets the routed agent's name — that's the
                // agent whose output became the Branch's typed output. Falls
                // back to source.name when the route was built outside
                // BranchBuilder (no recorded routedAgentName).
                terminalAgentId = route.routedAgentName ?: branch.source.name

                val output: OUT = route.sessionExecutor?.invoke(sourceOut, emitter)
                    ?: route.executor(sourceOut)

                channel.send(AgentEvent.Completed(terminalAgentId, output, null))
                channel.close()
                result.complete(output)
            } catch (timeout: TimeoutCancellationException) {
                // #2863 — caught BEFORE CancellationException (subtype).
                channel.send(AgentEvent.Failed(terminalAgentId, timeout))
                channel.close()
                result.completeExceptionally(timeout)
            } catch (cancel: CancellationException) {
                // #2863 — bare cancellation propagates per structured concurrency.
                result.completeExceptionally(cancel)
                channel.close(cancel)
                throw cancel
            } catch (t: Throwable) {
                channel.send(AgentEvent.Failed(terminalAgentId, t))
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

// #2806 — visible drops on the non-suspending emitter path.
private val BRANCH_LOGGER: Logger = Logger.getLogger("agents_engine.composition.branch.BranchSession")
