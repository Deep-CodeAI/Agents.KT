package agents_engine.composition.loop

import agents_engine.core.AgentRuntimeContext
import agents_engine.core.withAgentRuntimeContext
import agents_engine.model.AgentEventEmitter
import agents_engine.runtime.events.AgentEvent
import agents_engine.runtime.events.AgentSession
import agents_engine.runtime.events.withRuntimeContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch

/**
 * `agents_engine/composition/loop/LoopSessionExtension.kt` — the
 * `loop.session(input)` extension. Each iteration runs via the loop's
 * `sessionExec`, streaming the wrapped agent's events with the
 * agent's `agentId`. Iterations interleave only one at a time
 * (loops are sequential). Termination as in the non-streaming path:
 * `next` returns `null` or `maxIterations` hit. Terminal `Completed`
 * carries `loopAgentId` (or `"loop"` fallback) (#1749). See
 * `src/main/resources/internals-agent/composition/loop/LoopSessionExtension.md`
 * (#1837 / #1870).
 */

/**
 * #1749 — start a streaming session against [this] loop.
 *
 * Each iteration runs the wrapped agent (or pipeline) under the same
 * emitter, so the consumer sees bracket events repeated per iteration
 * with the same `agentId`. The loop terminates when `next(out)` returns
 * null OR when `maxIterations` is reached (the latter throws, surfacing
 * as `AgentEvent.Failed`).
 *
 * Terminal `Completed` uses `loopAgentId` — the wrapped agent's name
 * (or the pipeline's last agent's name). Falls back to `"loop"` when
 * the Loop was constructed outside the factory functions.
 */
fun <IN, OUT> Loop<IN, OUT>.session(input: IN): AgentSession<OUT> {
    val loop = this
    val channel = Channel<AgentEvent<OUT>>(Channel.BUFFERED)
    val result = CompletableDeferred<OUT>()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    val terminalAgentId = loop.loopAgentId ?: "loop"
    val runtimeContext = AgentRuntimeContext(sessionId = java.util.UUID.randomUUID().toString())

    scope.launch {
        withAgentRuntimeContext(runtimeContext) {
            @Suppress("UNCHECKED_CAST")
            val emitter: AgentEventEmitter = { event ->
                channel.trySend(event.withRuntimeContext(runtimeContext) as AgentEvent<OUT>)
            }
            try {
                // sessionExec streams the wrapped run's inner events per
                // iteration; falls back to plain execution (no events) when
                // the Loop was constructed without the factory functions.
                val streamingExec: suspend (IN) -> OUT = loop.sessionExec?.let { f -> { input -> f(input, emitter) } }
                    ?: loop.execution

                var current = streamingExec(input)
                var iterations = 1
                while (true) {
                    val feedback = loop.next(current)
                    if (feedback == null) break
                    check(iterations < loop.maxIterations) {
                        "Loop exceeded maxIterations=${loop.maxIterations} without termination."
                    }
                    current = streamingExec(feedback)
                    iterations++
                }

                channel.trySend(AgentEvent.Completed(terminalAgentId, current, null))
                channel.close()
                result.complete(current)
            } catch (t: Throwable) {
                channel.trySend(AgentEvent.Failed(terminalAgentId, t))
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
