package agents_engine.composition.parallel

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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch

/**
 * `agents_engine/composition/parallel/ParallelSessionExtension.kt` — the
 * `parallel.session(input)` extension. All branches run concurrently
 * via `sessionExecutions`, their events interleave on the shared
 * channel — demultiplex by `agentId` on the consumer side. Terminal
 * `Completed` fires once with the full `List<OUT>` after all branches
 * complete. `Failed` if any branch throws (#1750). See
 * `src/main/resources/internals-agent/composition/parallel/ParallelSessionExtension.md`
 * (#1837 / #1872).
 */

/**
 * #1750 — start a streaming session against [this] parallel composition.
 *
 * All branches run concurrently under `Dispatchers.Default`. Each branch
 * emits its events into the SAME emitter, so the consumer's Flow sees
 * interleaved events from all branches in arrival order. Every event
 * carries its branch's `agentId` so the consumer can demultiplex.
 *
 * Terminal `Completed.agentId` is the literal `"parallel"` — Parallel
 * has no single output-producing agent. The terminal `output` is the
 * `List<OUT>` in the same order as the branches (matches the
 * non-streaming `invokeSuspend` contract).
 *
 * Failure: if any branch throws, the others may continue briefly until
 * their `async` is cancelled by the failed branch's exception
 * propagating through `awaitAll`. Events from those branches that already
 * fired stay in the Flow (no retroactive removal — that's Flow semantics).
 * Terminal becomes `Failed` with the first branch's exception.
 *
 * Fallback: branches built outside the `/` factory may not have
 * `sessionExecutions` populated; in that case the parallel run executes
 * non-streaming and only the terminal `Completed`/`Failed` fires.
 */
fun <IN, OUT> Parallel<IN, OUT>.session(input: IN): AgentSession<List<OUT>> {
    val parallel = this
    val channel = Channel<AgentEvent<List<OUT>>>(Channel.BUFFERED)
    val result = CompletableDeferred<List<OUT>>()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    val runtimeContext = AgentRuntimeContext(sessionId = java.util.UUID.randomUUID().toString())

    scope.launch {
        withAgentRuntimeContext(runtimeContext) {
            @Suppress("UNCHECKED_CAST")
            val emitter: AgentEventEmitter = { event ->
                channel.trySend(event.withRuntimeContext(runtimeContext) as AgentEvent<List<OUT>>)
            }
            try {
                val outputs = coroutineScope {
                    val sessionExecs = parallel.sessionExecutions
                    if (sessionExecs != null) {
                        // Streaming path: each branch async with shared emitter.
                        sessionExecs.map { exec ->
                            async(Dispatchers.Default) { exec(input, emitter) }
                        }.awaitAll()
                    } else {
                        // Fallback: no per-branch streaming. Just run executions.
                        parallel.executions.map { exec ->
                            async(Dispatchers.Default) { exec(input) }
                        }.awaitAll()
                    }
                }

                channel.trySend(AgentEvent.Completed("parallel", outputs, null))
                channel.close()
                result.complete(outputs)
            } catch (t: Throwable) {
                channel.trySend(AgentEvent.Failed("parallel", t))
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
