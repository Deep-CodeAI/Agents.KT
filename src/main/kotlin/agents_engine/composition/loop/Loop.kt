package agents_engine.composition.loop

import agents_engine.core.*
import agents_engine.composition.pipeline.Pipeline
import kotlinx.coroutines.runBlocking

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
        // #1749: pipeline's effectiveSessionExec streams every stage's events per iteration.
        sessionExec = { input, emitter -> inner.effectiveSessionExec(input, emitter) },
        loopAgentId = inner.agents.lastOrNull()?.name,
    )
}
