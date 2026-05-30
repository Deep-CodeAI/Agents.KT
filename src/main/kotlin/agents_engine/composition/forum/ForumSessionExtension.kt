package agents_engine.composition.forum

import agents_engine.core.Agent
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * `agents_engine/composition/forum/ForumSessionExtension.kt` — the
 * `forum.session(input)` extension. All participants run concurrently
 * via `runAgentInSession`, their events interleave on the shared
 * channel demultiplexable by `agentId`. The optional transcript-captain
 * runs after the deliberation completes; its events also flow through.
 * Terminal `Completed` carries the forum's effective output. See
 * `src/main/resources/internals-agent/composition/forum/ForumSessionExtension.md`
 * (#1837 / #1868).
 */

/**
 * #1751 — start a streaming session against [this] forum.
 *
 * Participants run concurrently — their events stream into the shared
 * emitter and interleave by arrival order (like Parallel). After every
 * participant completes, the captain runs sequentially; the captain's
 * events stream next. Terminal `Completed(agentId=captain.name, output)`.
 *
 * Preserves the `ForumReturnException` short-circuit: if a participant
 * (or, less commonly, the captain) calls `forum_return`, the captain
 * doesn't run; terminal `Completed` carries the captured value cast
 * through `castForumReturnInternal`.
 *
 * Mention listener still fires per-agent (forum.onMentionEmitted) — the
 * streaming session is purely additive to the existing observability.
 */
fun <IN, OUT> Forum<IN, OUT>.session(input: IN): AgentSession<OUT> {
    val forum = this
    val channel = Channel<AgentEvent<OUT>>(Channel.BUFFERED)
    val result = CompletableDeferred<OUT>()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    val captain = forum.agents.last()
    val participants = forum.agents.dropLast(1)
    val runtimeContext = AgentRuntimeContext(sessionId = java.util.UUID.randomUUID().toString())

    scope.launch {
        withAgentRuntimeContext(runtimeContext) {
            @Suppress("UNCHECKED_CAST")
            val emitter: AgentEventEmitter = { event ->
                channel.trySend(event.withRuntimeContext(runtimeContext) as AgentEvent<OUT>)
            }
            try {
                val verdict: OUT = try {
                    val contributions = withContext(Dispatchers.Default) {
                        coroutineScope {
                            participants.map { participant ->
                                async {
                                    @Suppress("UNCHECKED_CAST")
                                    val out = runAgentInSession(
                                        participant as Agent<IN, Any?>,
                                        input,
                                        emitter,
                                    ).first
                                    forum.fireMentionListener(participant.name, out)
                                    ParticipantContribution(participant.name, out)
                                }
                            }.awaitAll()
                        }
                    }
                    val captainVerdict: OUT = if (forum.captainTakesTranscript) {
                        val transcript = ForumTranscript(originalInput = input, contributions = contributions)
                        @Suppress("UNCHECKED_CAST")
                        runAgentInSession(
                            captain as Agent<ForumTranscript<IN>, OUT>,
                            transcript,
                            emitter,
                        ).first
                    } else {
                        @Suppress("UNCHECKED_CAST")
                        runAgentInSession(
                            captain as Agent<IN, OUT>,
                            input,
                            emitter,
                        ).first
                    }
                    forum.fireMentionListener(captain.name, captainVerdict)
                    captainVerdict
                } catch (e: ForumReturnException) {
                    forum.castForumReturnInternal(e.value)
                }

                channel.trySend(AgentEvent.Completed(captain.name, verdict, null))
                channel.close()
                result.complete(verdict)
            } catch (t: Throwable) {
                // #2863 — propagate bare cancellation; keep timeout on Failed.
                if (t is CancellationException && t !is TimeoutCancellationException) {
                    result.completeExceptionally(t)
                    channel.close(t)
                    throw t
                }
                channel.trySend(AgentEvent.Failed(captain.name, t))
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
