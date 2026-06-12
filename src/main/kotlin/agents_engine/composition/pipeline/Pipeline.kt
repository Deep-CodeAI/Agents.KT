package agents_engine.composition.pipeline

import agents_engine.core.*
import agents_engine.composition.branch.Branch
import agents_engine.composition.forum.Forum
import agents_engine.composition.loop.Loop
import agents_engine.composition.parallel.Parallel
import kotlinx.coroutines.runBlocking

/**
 * `agents_engine/composition/pipeline/Pipeline.kt` — sequential
 * composition via the `then` infix. `agentA then agentB` produces a
 * `Pipeline<IN, OUT>` that runs A, feeds its output into B, returns
 * B's output. Many `then` overloads exist for chaining
 * Agent/Pipeline/Forum/Loop/Branch/Parallel onto each other. The
 * single suspending `execution` lambda lets the framework cross-call
 * other composition operators in one coroutine (no nested
 * runBlocking — #638). Session-aware `sessionExec` (#1745) streams
 * inner agents' events with their own `agentId`s. See
 * `src/main/resources/internals-agent/composition/pipeline/Pipeline.md`
 * (#1837 / #1873).
 */

/**
 * #638: `execution` is suspend so internal cross-calls between Pipeline / Forum /
 * Parallel / Loop / Branch can chain in one coroutine — no nested `runBlocking`.
 * The blocking [invoke] is a one-line shim that wraps `runBlocking` exactly once,
 * at the user-visible call boundary.
 */
class Pipeline<IN, OUT>(
    val agents: List<Agent<*, *>>,
    /**
     * #1745 — session-aware execution path. When `pipeline.session(input)`
     * is called, this runs instead of [execution] with a non-null emitter,
     * surfacing inner-agent events with their own `agentId`s. Defaults to
     * the non-streaming `execution` so any `then` overload that hasn't been
     * converted yet still works (Pipeline session emits only the terminal
     * Completed, no inner events — known gap, see #1745 follow-ups).
     *
     * Declared BEFORE [execution] so the trailing-lambda construction
     * `Pipeline(agents) { ... }` still binds the lambda to [execution].
     */
    internal val sessionExec: (suspend (
        input: IN,
        emitter: agents_engine.model.AgentEventEmitter,
    ) -> OUT)? = null,
    private val execution: suspend (IN) -> OUT,
) {
    /** Effective sessionExec: explicit when supplied, otherwise falls back to execution (no events). */
    internal val effectiveSessionExec: suspend (
        input: IN,
        emitter: agents_engine.model.AgentEventEmitter,
    ) -> OUT = sessionExec ?: { input, _ -> execution(input) }

    operator fun invoke(input: IN): OUT = runBlocking { execution(input) }

    suspend fun invokeSuspend(input: IN): OUT = execution(input)
}

infix fun <A, B, C> Agent<A, B>.then(other: Agent<B, C>): Pipeline<A, C> {
    this.markPlaced("pipeline")
    other.markPlaced("pipeline")
    val first = this
    return Pipeline(
        agents = listOf(first, other),
        // #1745: streaming path runs both agents through runAgentInSession
        // so events from both flow into the emitter with their own agentIds.
        sessionExec = { input, emitter ->
            val mid = staged(first.name, emitter) {
                agents_engine.runtime.events.runAgentInSession(first, input, emitter).first
            }
            staged(other.name, emitter) {
                agents_engine.runtime.events.runAgentInSession(other, mid, emitter).first
            }
        },
        execution = { input -> other.invokeSuspend(first.invokeSuspend(input)) },
    )
}

infix fun <A, B, C> Pipeline<A, B>.then(other: Agent<B, C>): Pipeline<A, C> {
    other.markPlaced("pipeline")
    val inner = this
    return Pipeline(
        agents = inner.agents + other,
        // #1746: chain the inner Pipeline's streaming output into the new
        // Agent's session run. Inner Pipeline's effectiveSessionExec
        // forwards events from each of its stages; runAgentInSession then
        // emits the trailing Agent's bracket events.
        sessionExec = { input, emitter ->
            val mid = inner.effectiveSessionExec(input, emitter)
            staged(other.name, emitter) {
                agents_engine.runtime.events.runAgentInSession(other, mid, emitter).first
            }
        },
        execution = { input -> other.invokeSuspend(inner.invokeSuspend(input)) },
    )
}

infix fun <A, B, C> Agent<A, B>.then(other: Forum<B, C>): Pipeline<A, C> {
    this.markPlaced("pipeline")
    val first = this
    return Pipeline(
        agents = listOf(first) + other.agents,
        // #3866: stream the leading agent, then the forum's participants + captain.
        sessionExec = { input, emitter ->
            val mid = staged(first.name, emitter) {
                agents_engine.runtime.events.runAgentInSession(first, input, emitter).first
            }
            staged("forum", emitter) { other.sessionInvoke(mid, emitter) }
        },
        execution = { input -> other.invokeSuspend(first.invokeSuspend(input)) },
    )
}

infix fun <A, B, C> Pipeline<A, B>.then(other: Forum<B, C>): Pipeline<A, C> {
    val inner = this
    return Pipeline(
        agents = inner.agents + other.agents,
        // #3866: chain the pipeline's streaming stages into the forum's streaming deliberation.
        sessionExec = { input, emitter ->
            val mid = inner.effectiveSessionExec(input, emitter)
            staged("forum", emitter) { other.sessionInvoke(mid, emitter) }
        },
        execution = { input -> other.invokeSuspend(inner.invokeSuspend(input)) },
    )
}

infix fun <A, B, C> Pipeline<A, B>.then(other: Pipeline<B, C>): Pipeline<A, C> {
    val left = this
    return Pipeline(
        agents = left.agents + other.agents,
        // #1746: chain both pipelines' streaming exec paths.
        sessionExec = { input, emitter ->
            val mid = left.effectiveSessionExec(input, emitter)
            other.effectiveSessionExec(mid, emitter)
        },
        execution = { input -> other.invokeSuspend(left.invokeSuspend(input)) },
    )
}

infix fun <A, B, C> Agent<A, B>.then(other: Parallel<B, C>): Pipeline<A, List<C>> {
    this.markPlaced("pipeline")
    val first = this
    return Pipeline(
        agents = listOf(first) + other.agents,
        // #3866: stream the leading agent, then fan out — branch events interleave.
        sessionExec = { input, emitter ->
            val mid = staged(first.name, emitter) {
                agents_engine.runtime.events.runAgentInSession(first, input, emitter).first
            }
            staged("parallel", emitter) { other.sessionInvoke(mid, emitter) }
        },
        execution = { input -> other.invokeSuspend(first.invokeSuspend(input)) },
    )
}

infix fun <A, B, C> Pipeline<A, B>.then(other: Parallel<B, C>): Pipeline<A, List<C>> {
    val inner = this
    return Pipeline(
        agents = inner.agents + other.agents,
        // #3866: chain the pipeline's streaming stages into the parallel fan-out.
        sessionExec = { input, emitter ->
            val mid = inner.effectiveSessionExec(input, emitter)
            staged("parallel", emitter) { other.sessionInvoke(mid, emitter) }
        },
        execution = { input -> other.invokeSuspend(inner.invokeSuspend(input)) },
    )
}

infix fun <A, B, C> Parallel<A, B>.then(other: Agent<List<B>, C>): Pipeline<A, C> {
    other.markPlaced("pipeline")
    val fanOut = this
    return Pipeline(
        agents = fanOut.agents + other,
        // #3866: fan-out branches stream first (interleaved), then the reducer agent.
        sessionExec = { input, emitter ->
            val mids = staged("parallel", emitter) { fanOut.sessionInvoke(input, emitter) }
            staged(other.name, emitter) {
                agents_engine.runtime.events.runAgentInSession(other, mids, emitter).first
            }
        },
        execution = { input -> other.invokeSuspend(fanOut.invokeSuspend(input)) },
    )
}

infix fun <A, B, C> Parallel<A, B>.then(other: Pipeline<List<B>, C>): Pipeline<A, C> {
    val fanOut = this
    return Pipeline(
        agents = fanOut.agents + other.agents,
        // #3866: fan-out branches stream first (interleaved), then the trailing pipeline's stages.
        sessionExec = { input, emitter ->
            val mids = staged("parallel", emitter) { fanOut.sessionInvoke(input, emitter) }
            other.effectiveSessionExec(mids, emitter)
        },
        execution = { input -> other.invokeSuspend(fanOut.invokeSuspend(input)) },
    )
}

infix fun <A, B, C> Agent<A, B>.then(other: Loop<B, C>): Pipeline<A, C> {
    this.markPlaced("pipeline")
    val first = this
    return Pipeline(
        agents = listOf(first),
        // #3866: stream the leading agent, then every loop iteration's events.
        sessionExec = { input, emitter ->
            val mid = staged(first.name, emitter) {
                agents_engine.runtime.events.runAgentInSession(first, input, emitter).first
            }
            staged("loop", emitter) { other.sessionInvoke(mid, emitter) }
        },
        execution = { input -> other.invokeSuspend(first.invokeSuspend(input)) },
    )
}

infix fun <A, B, C> Pipeline<A, B>.then(other: Loop<B, C>): Pipeline<A, C> {
    val inner = this
    return Pipeline(
        agents = inner.agents,
        // #3866: chain streaming stages into the loop's per-iteration streaming.
        sessionExec = { input, emitter ->
            val mid = inner.effectiveSessionExec(input, emitter)
            staged("loop", emitter) { other.sessionInvoke(mid, emitter) }
        },
        execution = { input -> other.invokeSuspend(inner.invokeSuspend(input)) },
    )
}

infix fun <A, B, C> Loop<A, B>.then(other: Agent<B, C>): Pipeline<A, C> {
    other.markPlaced("pipeline")
    val head = this
    return Pipeline(
        agents = listOf(other),
        // #3866: loop iterations stream first, then the trailing agent.
        sessionExec = { input, emitter ->
            val mid = staged("loop", emitter) { head.sessionInvoke(input, emitter) }
            staged(other.name, emitter) {
                agents_engine.runtime.events.runAgentInSession(other, mid, emitter).first
            }
        },
        execution = { input -> other.invokeSuspend(head.invokeSuspend(input)) },
    )
}

infix fun <A, B, C> Loop<A, B>.then(other: Pipeline<B, C>): Pipeline<A, C> {
    val head = this
    return Pipeline(
        agents = other.agents,
        // #3866: loop iterations stream first, then the trailing pipeline's stages.
        sessionExec = { input, emitter ->
            val mid = staged("loop", emitter) { head.sessionInvoke(input, emitter) }
            other.effectiveSessionExec(mid, emitter)
        },
        execution = { input -> other.invokeSuspend(head.invokeSuspend(input)) },
    )
}

infix fun <A, B, C> Agent<A, B>.then(other: Branch<B, C>): Pipeline<A, C> {
    this.markPlaced("pipeline")
    val first = this
    return Pipeline(
        agents = listOf(first),
        // #3866: stream the leading agent, then the branch source + routed agent.
        sessionExec = { input, emitter ->
            val mid = staged(first.name, emitter) {
                agents_engine.runtime.events.runAgentInSession(first, input, emitter).first
            }
            staged("branch", emitter) { other.sessionInvoke(mid, emitter) }
        },
        execution = { input -> other.invokeSuspend(first.invokeSuspend(input)) },
    )
}

infix fun <A, B, C> Pipeline<A, B>.then(other: Branch<B, C>): Pipeline<A, C> {
    val inner = this
    return Pipeline(
        agents = inner.agents,
        // #3866: chain streaming stages into the branch's streaming run.
        sessionExec = { input, emitter ->
            val mid = inner.effectiveSessionExec(input, emitter)
            staged("branch", emitter) { other.sessionInvoke(mid, emitter) }
        },
        execution = { input -> other.invokeSuspend(inner.invokeSuspend(input)) },
    )
}

infix fun <A, B, C> Branch<A, B>.then(other: Agent<B, C>): Pipeline<A, C> {
    other.markPlaced("pipeline")
    val head = this
    return Pipeline(
        agents = listOf(other),
        // #3866: branch source + routed agent stream first, then the trailing agent.
        sessionExec = { input, emitter ->
            val mid = staged("branch", emitter) { head.sessionInvoke(input, emitter) }
            staged(other.name, emitter) {
                agents_engine.runtime.events.runAgentInSession(other, mid, emitter).first
            }
        },
        execution = { input -> other.invokeSuspend(head.invokeSuspend(input)) },
    )
}

infix fun <A, B, C> Branch<A, B>.then(other: Pipeline<B, C>): Pipeline<A, C> {
    val head = this
    return Pipeline(
        agents = other.agents,
        // #3866: branch source + routed agent stream first, then the trailing pipeline's stages.
        sessionExec = { input, emitter ->
            val mid = staged("branch", emitter) { head.sessionInvoke(input, emitter) }
            other.effectiveSessionExec(mid, emitter)
        },
        execution = { input -> other.invokeSuspend(head.invokeSuspend(input)) },
    )
}

/**
 * #4491 — explicit stage boundaries on the session stream: wraps one direct
 * pipeline component (an agent or an operator leg) in
 * [agents_engine.runtime.events.AgentEvent.StageStarted] /
 * [agents_engine.runtime.events.AgentEvent.StageCompleted]. Nested
 * pipelines are NOT wrapped — their own `sessionExec` marks their stages,
 * so markers never nest or duplicate.
 */
internal suspend fun <T> staged(
    stageName: String,
    emitter: agents_engine.model.AgentEventEmitter,
    block: suspend () -> T,
): T {
    emitter(agents_engine.runtime.events.AgentEvent.StageStarted(stageName, stageName))
    val out = block()
    emitter(agents_engine.runtime.events.AgentEvent.StageCompleted(stageName, stageName))
    return out
}
