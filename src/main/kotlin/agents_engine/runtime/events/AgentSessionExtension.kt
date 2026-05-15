package agents_engine.runtime.events

import agents_engine.core.Agent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch

/**
 * #1736 — start a streaming session against [this] agent.
 *
 * Returns an [AgentSession] whose [AgentSession.events] is a cold flow of
 * typed [AgentEvent]s and whose [AgentSession.await] surfaces the typed
 * output (or rethrows the failure).
 *
 * **Step 2 scope (intentional):** for `implementedBy` skills the emitted
 * sequence is `SkillStarted → SkillCompleted → Completed`. For agentic
 * skills the same three events bracket the agentic loop, but [AgentEvent.Token]
 * and `ToolCall*` events are NOT yet surfaced — that's step 3, where the
 * agentic loop itself is rewired onto a `FlowCollector<AgentEvent>`.
 *
 * Cold flow: each call starts a fresh invocation. To replay events to
 * multiple collectors, wrap with `events.shareIn(...)`.
 */
fun <IN, OUT> Agent<IN, OUT>.session(input: IN): AgentSession<OUT> {
    val agent = this
    // BUFFERED keeps event production decoupled from consumer pace; an
    // implementedBy skill can complete and queue all four events before
    // the collector starts pulling. Step 3 may tune this for the
    // token-streaming case.
    val channel = Channel<AgentEvent<OUT>>(Channel.BUFFERED)
    val result = CompletableDeferred<OUT>()

    // Dedicated scope per session so a cancelled collector doesn't leak
    // the result coroutine. SupervisorJob keeps the session independent
    // of any unrelated coroutine the consumer happens to be running in.
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    scope.launch {
        // Captured-on-the-stack: each session has its own holder, so
        // concurrent sessions can't race on a shared field. Step 3's
        // agentic-loop rewire moves skill-name tracking into the
        // FlowCollector chain proper.
        var capturedSkillName: String? = null
        try {
            val output = agent.invokeSuspendForSession(input) { skillName ->
                capturedSkillName = skillName
                channel.trySend(AgentEvent.SkillStarted(agent.name, skillName))
            }
            channel.trySend(AgentEvent.SkillCompleted(agent.name, capturedSkillName ?: "?", null))
            channel.trySend(AgentEvent.Completed(agent.name, output, null))
            channel.close()
            result.complete(output)
        } catch (t: Throwable) {
            channel.trySend(AgentEvent.Failed(agent.name, t))
            channel.close()
            result.completeExceptionally(t)
        }
    }

    return AgentSession(
        events = channel.consumeAsFlow(),
        resultDeferred = result,
    )
}
