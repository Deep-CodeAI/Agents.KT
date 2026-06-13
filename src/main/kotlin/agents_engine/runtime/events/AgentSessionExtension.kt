package agents_engine.runtime.events

import agents_engine.core.Agent
import agents_engine.core.AgentRuntimeContext
import agents_engine.core.withAgentRuntimeContext
import agents_engine.model.AgentEventEmitter
import agents_engine.model.TokenUsage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import java.util.logging.Logger

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
    // #4496 — drops are counted, not logged per event: one summary line at
    // close, live count on AgentSession.droppedEvents.
    val drops = SessionDropCounter(
        SESSION_LOGGER,
        "agent='${agent.name}', sessionId='${runtimeContext.sessionId}'",
    )
    scope.launch {
        withAgentRuntimeContext(runtimeContext) {
            // #1739 / #2806 / #4496: emitter forwards AgentEvents from inside
            // the agentic loop (Token, ToolCallStarted, ToolCallArgumentsDelta,
            // ToolCallFinished). The emitter is a non-suspending lambda type
            // (AgentEventEmitter = (AgentEvent<*>) -> Unit), so the inner
            // path uses trySend; failures are aggregated by the drop counter
            // (one summary at close) instead of one warning per lost event.
            // Bracket events (Completed / Failed) below use suspending
            // `send` — they MUST be delivered to terminate the session.
            @Suppress("UNCHECKED_CAST")
            val emitter: AgentEventEmitter = { event ->
                val typed = event as AgentEvent<OUT>
                if (channel.trySend(typed).isFailure) {
                    drops.recordDrop(typed::class.simpleName ?: "?")
                }
            }
            try {
                val (output, usage) = runAgentInSession(agent, input, emitter)
                val completed = AgentEvent.Completed(agent.name, output, usage, runtimeContext)
                agent.fireAgentEvent(completed)
                channel.send(completed)
                channel.close()
                result.complete(output)
            } catch (timeout: TimeoutCancellationException) {
                // #2863 — TimeoutCancellationException must be caught BEFORE
                // CancellationException (it extends it). A budget / withTimeout
                // breach is a real failure consumers must hear about, so it
                // rides the Failed path.
                val failed = AgentEvent.Failed(agent.name, timeout, runtimeContext)
                agent.fireAgentEvent(failed)
                channel.send(failed)
                channel.close()
                result.completeExceptionally(timeout)
            } catch (cancel: CancellationException) {
                // #2863 — bare CancellationException means the collector / scope
                // was cancelled. Propagate per structured-concurrency contract
                // and close the channel WITH the cancel; do NOT emit a
                // synthetic Failed event.
                result.completeExceptionally(cancel)
                channel.close(cancel)
                throw cancel
            } catch (t: Throwable) {
                val failed = AgentEvent.Failed(agent.name, t, runtimeContext)
                agent.fireAgentEvent(failed)
                channel.send(failed)
                channel.close()
                result.completeExceptionally(t)
            } finally {
                drops.logSummaryAtClose()
            }
        }
    }

    return AgentSession(
        // #4499 — the producer runs in a detached scope; tie its lifecycle to collection so
        // cancelling (or completing early, e.g. take(1)) the events flow cancels the invocation
        // instead of leaving it running model calls in the background. The teardown lives in this
        // flow builder's `finally` — which runs in the collector's own frame on normal completion,
        // an exception, AND external cancellation of the collecting coroutine. (A downstream
        // `onCompletion` stage is skipped when the collector is cancelled from outside.)
        events = flow {
            try {
                channel.consumeAsFlow().collect { emit(it) }
            } finally {
                scope.cancel()
            }
        },
        resultDeferred = result,
        dropCounter = drops,
        cancelProducer = { scope.cancel() },
    )
}

// #2806 / #4496 — JUL logger for the one-line drop summary at session close.
// Only fires when the buffer filled and events were lost, which is rare on
// Channel.BUFFERED (64 cap) but worth surfacing when it happens.
private val SESSION_LOGGER: Logger = Logger.getLogger("agents_engine.runtime.events.AgentSession")

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
        request = agents_engine.core.RunRequest(promptOverride = promptOverride),
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
