package agents_engine.composition.loop

import agents_engine.runtime.events.AgentSession
import agents_engine.runtime.events.agentSessionScope

/**
 * `agents_engine/composition/loop/LoopSessionExtension.kt` — the
 * `loop.session(input)` extension. Each iteration runs via the loop's
 * `sessionExec`, streaming the wrapped agent's events with the
 * agent's `agentId`. Iterations interleave only one at a time
 * (loops are sequential). Termination as in the non-streaming path:
 * `next` returns `null` or `maxIterations` hit. Terminal `Completed`
 * carries `loopAgentId` (or `"loop"` fallback) (#1749). The channel /
 * scope / context / terminal-event lifecycle lives in the shared
 * [agentSessionScope] (#2797). See
 * `src/main/resources/internals-agent/composition/loop/LoopSessionExtension.md`
 * (#1837 / #1870).
 */

/**
 * #1749 — start a streaming session against [this] loop.
 *
 * Each iteration runs the wrapped agent (or pipeline) under the same
 * emitter, so the consumer sees bracket events repeated per iteration
 * with the same `agentId`. The loop terminates when `next(out)` returns
 * null OR when `maxIterations` is reached (the latter throws, surfacing
 * as `AgentEvent.Failed`).
 *
 * Terminal `Completed` uses `loopAgentId` — the wrapped agent's name
 * (or the pipeline's last agent's name). Falls back to `"loop"` when
 * the Loop was constructed outside the factory functions.
 */
fun <IN, OUT> Loop<IN, OUT>.session(input: IN): AgentSession<OUT> {
    val loop = this
    return agentSessionScope({ loop.loopAgentId ?: "loop" }) { emit ->
        // sessionExec streams the wrapped run's inner events per iteration; falls back to plain
        // execution (no events) when the Loop was constructed without the factory functions.
        val streamingExec: suspend (IN) -> OUT =
            loop.sessionExec?.let { f -> { i: IN -> f(i, emit) } } ?: loop.execution

        var current = streamingExec(input)
        var iterations = 1
        while (true) {
            val feedback = loop.next(current) ?: break
            check(iterations < loop.maxIterations) {
                "Loop exceeded maxIterations=${loop.maxIterations} without termination."
            }
            current = streamingExec(feedback)
            iterations++
        }
        current
    }
}
