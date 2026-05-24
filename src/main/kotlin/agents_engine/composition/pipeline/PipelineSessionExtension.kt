package agents_engine.composition.pipeline

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
 * `agents_engine/composition/pipeline/PipelineSessionExtension.kt` — the
 * `pipeline.session(input)` extension. Runs the pipeline's
 * `effectiveSessionExec` (which falls back to the non-streaming
 * `execution` for un-converted `then` overloads, surfacing only the
 * terminal Completed). Inner agents' events stream with their own
 * `agentId`s. Terminal `Completed` carries the pipeline's final
 * output (#1745). See
 * `src/main/resources/internals-agent/composition/pipeline/PipelineSessionExtension.md`
 * (#1837 / #1874).
 */

/**
 * #1745 — start a streaming session against [this] pipeline.
 *
 * Sequential composition: each inner agent runs to completion before the
 * next starts (the typed boundary forces a complete `MID` value to flow
 * from `a` to `b`). But WITHIN each agent, events stream incrementally:
 * the consumer sees `SkillStarted` / `Token` / `ToolCall*` / `SkillCompleted`
 * for `a`, then the same for `b`, terminated by exactly one `Completed`
 * with the pipeline's final `OUT`.
 *
 * `agentId` on every inner event names the source agent — composition
 * preserves provenance. The terminal `Completed.agentId` uses the LAST
 * agent's name (its `OUT` type matches the pipeline's `OUT`).
 *
 * **Step-4 scope:** only the `Agent then Agent` overload populates
 * `Pipeline.sessionExec` today. Multi-stage chains (`a then b then c`)
 * built via the `Pipeline then Agent` overload fall back to the default
 * (non-streaming) `sessionExec` — `pipeline.session(input)` will emit only
 * the terminal `Completed`. Separate follow-ups flip each composing
 * overload. Documented in the corresponding session test.
 */
fun <IN, OUT> Pipeline<IN, OUT>.session(input: IN): AgentSession<OUT> {
    val pipeline = this
    val channel = Channel<AgentEvent<OUT>>(Channel.BUFFERED)
    val result = CompletableDeferred<OUT>()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    val runtimeContext = AgentRuntimeContext(sessionId = java.util.UUID.randomUUID().toString())
    // agentId for the terminal Completed: last agent's name (its OUT
    // matches Pipeline's OUT). Pipeline has no name of its own.
    val terminalAgentId = pipeline.agents.lastOrNull()?.name ?: "pipeline"

    scope.launch {
        withAgentRuntimeContext(runtimeContext) {
            @Suppress("UNCHECKED_CAST")
            val emitter: AgentEventEmitter = { event ->
                channel.trySend(event.withRuntimeContext(runtimeContext) as AgentEvent<OUT>)
            }
            try {
                val output = pipeline.effectiveSessionExec(input, emitter)
                channel.trySend(AgentEvent.Completed(terminalAgentId, output, null))
                channel.close()
                result.complete(output)
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
