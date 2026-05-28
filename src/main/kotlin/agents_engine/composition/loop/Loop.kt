package agents_engine.composition.loop

import agents_engine.core.*
import agents_engine.composition.pipeline.Pipeline
import kotlinx.coroutines.runBlocking

/**
 * `agents_engine/composition/loop/Loop.kt` — feedback-loop operator.
 * Runs `execution(input)`, calls `next(output)` to derive the next
 * input (or `null` to terminate), repeats until `next` returns `null`
 * or `maxIterations` (default 1000) is hit. The session-aware variant
 * (#1749) streams each iteration's events with the wrapped agent's
 * own `agentId`. See
 * `src/main/resources/internals-agent/composition/loop/Loop.md`
 * (#1837 / #1869).
 */

private const val DEFAULT_MAX_ITERATIONS = 1_000

/**
 * #638: `execution` is suspend so the loop body composes cleanly with other suspending
 * operators. The user-supplied `next` callback stays sync — feedback functions are
 * pure logic; if a future use case needs suspending feedback the callback type can
 * widen without breaking callers.
 */
class Loop<IN, OUT>(
    internal val execution: suspend (IN) -> OUT,
    internal val next: (OUT) -> IN?,
    internal val maxIterations: Int = DEFAULT_MAX_ITERATIONS,
    val agents: List<Agent<*, *>> = emptyList(),
    /**
     * #1749 — session-aware execution path. When non-null and called via
     * `loop.session(input)`, each iteration's wrapped agent (or pipeline)
     * streams events through the emitter with its own `agentId`.
     */
    internal val sessionExec: (suspend (input: IN, emitter: agents_engine.model.AgentEventEmitter) -> OUT)? = null,
    /**
     * #1749 — `agentId` for the terminal `AgentEvent.Completed` emitted
     * by `loop.session(input)`. Null when constructed outside the factory
     * functions; the session falls back to `"loop"` in that case.
     */
    internal val loopAgentId: String? = null,
) {
    init {
        require(maxIterations > 0) { "Loop maxIterations must be greater than 0." }
    }

    operator fun invoke(input: IN): OUT = runBlocking { invokeSuspend(input) }

    suspend fun invokeSuspend(input: IN): OUT {
        var current = execution(input)
        var iterations = 1
        while (true) {
            val feedback = next(current) ?: return current
            check(iterations < maxIterations) {
                "Loop exceeded maxIterations=$maxIterations without termination."
            }
            current = execution(feedback)
            iterations++
        }
    }
}

fun <A, B> Agent<A, B>.loop(
    maxIterations: Int = DEFAULT_MAX_ITERATIONS,
    next: (B) -> A?,
): Loop<A, B> {
    this.markPlaced("loop")
    val agent = this
    return Loop(
        execution = { input -> agent.invokeSuspend(input) },
        next = next,
        maxIterations = maxIterations,
        agents = listOf(agent),
        // #1749: stream the wrapped agent's events per iteration.
        sessionExec = { input, emitter ->
            agents_engine.runtime.events.runAgentInSession(agent, input, emitter).first
        },
        loopAgentId = agent.name,
    )
}

fun <A, B> Pipeline<A, B>.loop(
    maxIterations: Int = DEFAULT_MAX_ITERATIONS,
    next: (B) -> A?,
): Loop<A, B> {
    val inner = this
    return Loop(
        execution = { input -> inner.invokeSuspend(input) },
        next = next,
        maxIterations = maxIterations,
        agents = inner.agents,
        // #1749: pipeline's effectiveSessionExec streams every stage's events per iteration.
        sessionExec = { input, emitter -> inner.effectiveSessionExec(input, emitter) },
        loopAgentId = inner.agents.lastOrNull()?.name,
    )
}

/**
 * #2420 — composition snapshot for Loop. Checkpoints after each completed
 * iteration so a crash mid-body never loses earlier iterations' progress.
 * The [CompositionSnapshot.stageIndex] doubles as "iterations completed"
 * here, and [CompositionSnapshot.intermediate] carries the value that
 * fed the iteration that crashed (so we can re-enter it on resume).
 *
 * v1 contract:
 * - Only `String → String` loops are supported (same as the Pipeline
 *   v1 — typed-intermediate encodings are deferred).
 * - The terminator [Loop.next] still owns the loop's exit condition.
 *   Resume seeds the loop with the saved intermediate and re-enters at
 *   the boundary BEFORE the next body call — meaning the iteration that
 *   crashed is re-executed exactly once. That's the "lose at most the
 *   last unit of work" invariant from the #2386 spec.
 */
suspend fun <IN, OUT> Loop<IN, OUT>.resumeOrStart(
    sessionId: String,
    input: IN,
    store: agents_engine.core.CompositionSnapshotStore,
): OUT {
    val seed = store.load(sessionId)
    val completedIterations = seed?.stageIndex ?: 0
    @Suppress("UNCHECKED_CAST")
    var nextInput: IN = (seed?.intermediate as? IN) ?: input

    var iterations = completedIterations
    while (true) {
        check(iterations < this.maxIterations) {
            "Loop exceeded maxIterations=${this.maxIterations} without termination."
        }
        val current = this.execution(nextInput)
        iterations++
        // Snapshot AFTER the iteration completes. We persist the value
        // that WILL feed the next iteration so the resume reads it back
        // as `nextInput`. If `next` decides to stop, this snapshot is the
        // terminal one (callers can inspect it; we never delete here).
        val feedback = this.next(current)
        if (feedback == null) {
            store.save(
                sessionId,
                agents_engine.core.CompositionSnapshot(
                    sessionId = sessionId,
                    stageIndex = iterations,
                    intermediate = current?.toString() ?: "",
                ),
            )
            return current
        }
        store.save(
            sessionId,
            agents_engine.core.CompositionSnapshot(
                sessionId = sessionId,
                stageIndex = iterations,
                intermediate = feedback.toString(),
            ),
        )
        nextInput = feedback
    }
}
