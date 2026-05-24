package agents_engine.runtime.events

import agents_engine.core.Agent
import agents_engine.core.AgentRuntimeContext
import agents_engine.core.withAgentRuntimeContext
import agents_engine.model.AgentEventEmitter
import agents_engine.model.TokenUsage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch

/**
 * `agents_engine/runtime/events/AgentSessionExtension.kt` — the
 * `agent.session(input)` extension entry point (#1736). Each call
 * builds a fresh `Channel.BUFFERED` + a `CompletableDeferred<OUT>`,
 * launches the agent under a `SupervisorJob() + Dispatchers.Default`
 * scope, threads an `AgentEventEmitter` through the streaming-aware
 * `invokeSuspendForSession` entry, and returns an [AgentSession]
 * wrapping the channel-as-flow and the deferred. Cold flow — fresh
 * invocation per call. See
 * `src/main/resources/internals-agent/runtime/events/AgentSessionExtension.md`
 * (#1837 / #1894).
 */

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
    val runtimeContext = agent.newRuntimeContext(sessionId = java.util.UUID.randomUUID().toString())
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
        withAgentRuntimeContext(runtimeContext) {
            // #1739: emitter forwards AgentEvents from inside the agentic loop
            // (Token, ToolCallStarted, ToolCallArgumentsDelta, ToolCallFinished)
            // into the same channel as the bracket events. trySend is non-
            // suspending — appropriate for a BUFFERED channel; if the buffer
            // ever fills (it has high capacity), excess events would be
            // dropped silently.
            @Suppress("UNCHECKED_CAST")
            val emitter: AgentEventEmitter = { event ->
                channel.trySend(event as AgentEvent<OUT>)
            }
            try {
                val (output, usage) = runAgentInSession(agent, input, emitter)
                val completed = AgentEvent.Completed(agent.name, output, usage, runtimeContext)
                agent.fireAgentEvent(completed)
                channel.trySend(completed)
                channel.close()
                result.complete(output)
            } catch (t: Throwable) {
                val failed = AgentEvent.Failed(agent.name, t, runtimeContext)
                agent.fireAgentEvent(failed)
                channel.trySend(failed)
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

/**
 * #1745 — shared "run an agent and surface its bracket + inner events
 * onto [emitter]" helper. Used by both `Agent.session(input)` and
 * `Pipeline.session(input)`. Emits `SkillStarted` and `SkillCompleted`
 * around the agent's execution; does NOT emit `Completed` (the composing
 * caller emits exactly one terminal `Completed` after all stages run).
 *
 * Returns the typed output paired with the cumulative `TokenUsage` for
 * this agent's skill (null for `implementedBy`).
 */
internal suspend fun <IN, OUT> runAgentInSession(
    agent: Agent<IN, OUT>,
    input: IN,
    emitter: AgentEventEmitter,
    /**
     * #1747 — when non-null, runs the agentic loop with this string as
     * the effective system prompt instead of `agent.prompt`. Used by the
     * `wrap` operator's session path (teacher's output becomes the
     * student's per-call system prompt).
     */
    promptOverride: String? = null,
): Pair<OUT, TokenUsage?> {
    var capturedSkillName: String? = null
    var capturedUsage: TokenUsage? = null
    val runtimeContext = AgentRuntimeContext.current()
    val notifyingEmitter: AgentEventEmitter = { event ->
        val contextual = runtimeContext?.let { event.withRuntimeContext(it) } ?: event
        agent.fireAgentEvent(contextual)
        emitter(contextual)
    }
    val output = agent.invokeSuspendForSession(
        input,
        emitter = notifyingEmitter,
        promptOverride = promptOverride,
        onSkillCompleted = { usage -> capturedUsage = usage },
    ) { skillName ->
        // SkillStarted fires BEFORE the skill body runs — emitting from
        // this onSkillStarted callback (non-suspend; emitter is also
        // non-suspend per #1745) means the event reaches the consumer
        // before any Token / ToolCall* events from this skill's loop.
        capturedSkillName = skillName
        notifyingEmitter(AgentEvent.SkillStarted(agent.name, skillName))
    }
    notifyingEmitter(AgentEvent.SkillCompleted(agent.name, capturedSkillName ?: "?", capturedUsage))
    return output to capturedUsage
}
