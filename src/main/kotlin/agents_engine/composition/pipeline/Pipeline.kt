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

/**
 * #2420 — composition snapshot for Pipeline. Walks the [Pipeline.agents]
 * list stage by stage, checkpointing into [store] after each stage
 * completes (so a crash mid-stage never loses the previous stage's
 * output). On resume, [store.load] tells us which stages to skip and
 * what intermediate value to seed the next stage with.
 *
 * v1 contract:
 * - Only `String → … → String` pipelines are supported. The
 *   [CompositionSnapshot.intermediate] field is a `String`; non-string
 *   intermediates will throw on the first stage transition.
 * - Each agent in [Pipeline.agents] is invoked through its plain
 *   `invokeSuspend(input)` path — composition snapshots are
 *   independent of any per-leaf-agent `persistence { }` config (the
 *   two persistence layers compose without coordination in v1).
 * - Only flat `Agent.then(Agent)` chains are exercised by tests so
 *   far. Nested `Pipeline.then(Pipeline)` constructions inherit the
 *   flattened [Pipeline.agents] list, so the same walk works — but
 *   that's not yet pinned by a test.
 */
suspend fun <IN, OUT> Pipeline<IN, OUT>.resumeOrStart(
    sessionId: String,
    input: IN,
    store: agents_engine.core.CompositionSnapshotStore,
): OUT {
    val seed = store.load(sessionId)
    val startIndex = seed?.stageIndex ?: 0

    // The intermediate at the boundary BEFORE stage `startIndex`. For a
    // fresh run that's just `input`; on resume it's the saved value.
    @Suppress("UNCHECKED_CAST")
    var current: Any? = seed?.intermediate ?: input

    for ((i, rawAgent) in this.agents.withIndex()) {
        if (i < startIndex) continue
        @Suppress("UNCHECKED_CAST")
        val agent = rawAgent as agents_engine.core.Agent<Any?, Any?>
        current = agent.invokeSuspend(current)
        // Checkpoint AFTER the stage completes — never mid-stage. The
        // save key advances past the stage we just finished so a resume
        // begins at the next one.
        store.save(
            sessionId,
            agents_engine.core.CompositionSnapshot(
                sessionId = sessionId,
                stageIndex = i + 1,
                intermediate = current?.toString() ?: "",
            ),
        )
    }
    @Suppress("UNCHECKED_CAST")
    return current as OUT
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
            val (mid, _) = agents_engine.runtime.events.runAgentInSession(first, input, emitter)
            val (out, _) = agents_engine.runtime.events.runAgentInSession(other, mid, emitter)
            out
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
            val (out, _) = agents_engine.runtime.events.runAgentInSession(other, mid, emitter)
            out
        },
        execution = { input -> other.invokeSuspend(inner.invokeSuspend(input)) },
    )
}

infix fun <A, B, C> Agent<A, B>.then(other: Forum<B, C>): Pipeline<A, C> {
    this.markPlaced("pipeline")
    return Pipeline(listOf(this) + other.agents) { input -> other.invokeSuspend(this.invokeSuspend(input)) }
}

infix fun <A, B, C> Pipeline<A, B>.then(other: Forum<B, C>): Pipeline<A, C> {
    return Pipeline(agents + other.agents) { input -> other.invokeSuspend(this.invokeSuspend(input)) }
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
    return Pipeline(listOf(this) + other.agents) { input -> other.invokeSuspend(this.invokeSuspend(input)) }
}

infix fun <A, B, C> Pipeline<A, B>.then(other: Parallel<B, C>): Pipeline<A, List<C>> {
    return Pipeline(agents + other.agents) { input -> other.invokeSuspend(this.invokeSuspend(input)) }
}

infix fun <A, B, C> Parallel<A, B>.then(other: Agent<List<B>, C>): Pipeline<A, C> {
    other.markPlaced("pipeline")
    return Pipeline(agents + other) { input -> other.invokeSuspend(this.invokeSuspend(input)) }
}

infix fun <A, B, C> Parallel<A, B>.then(other: Pipeline<List<B>, C>): Pipeline<A, C> {
    return Pipeline(agents + other.agents) { input -> other.invokeSuspend(this.invokeSuspend(input)) }
}

infix fun <A, B, C> Agent<A, B>.then(other: Loop<B, C>): Pipeline<A, C> {
    this.markPlaced("pipeline")
    return Pipeline(listOf(this)) { input -> other.invokeSuspend(this.invokeSuspend(input)) }
}

infix fun <A, B, C> Pipeline<A, B>.then(other: Loop<B, C>): Pipeline<A, C> =
    Pipeline(agents) { input -> other.invokeSuspend(this.invokeSuspend(input)) }

infix fun <A, B, C> Loop<A, B>.then(other: Agent<B, C>): Pipeline<A, C> {
    other.markPlaced("pipeline")
    return Pipeline(listOf(other)) { input -> other.invokeSuspend(this.invokeSuspend(input)) }
}

infix fun <A, B, C> Loop<A, B>.then(other: Pipeline<B, C>): Pipeline<A, C> =
    Pipeline(other.agents) { input -> other.invokeSuspend(this.invokeSuspend(input)) }

infix fun <A, B, C> Agent<A, B>.then(other: Branch<B, C>): Pipeline<A, C> {
    this.markPlaced("pipeline")
    return Pipeline(listOf(this)) { input -> other.invokeSuspend(this.invokeSuspend(input)) }
}

infix fun <A, B, C> Pipeline<A, B>.then(other: Branch<B, C>): Pipeline<A, C> =
    Pipeline(agents) { input -> other.invokeSuspend(this.invokeSuspend(input)) }

infix fun <A, B, C> Branch<A, B>.then(other: Agent<B, C>): Pipeline<A, C> {
    other.markPlaced("pipeline")
    return Pipeline(listOf(other)) { input -> other.invokeSuspend(this.invokeSuspend(input)) }
}

infix fun <A, B, C> Branch<A, B>.then(other: Pipeline<B, C>): Pipeline<A, C> =
    Pipeline(other.agents) { input -> other.invokeSuspend(this.invokeSuspend(input)) }
