package agents_engine.composition.pipeline

import agents_engine.runtime.events.AgentSession
import agents_engine.runtime.events.agentSessionScope

/**
 * `agents_engine/composition/pipeline/PipelineSessionExtension.kt` — the
 * `pipeline.session(input)` extension. Runs the pipeline's
 * `effectiveSessionExec` (which falls back to the non-streaming
 * `execution` for un-converted `then` overloads, surfacing only the
 * terminal Completed). Inner agents' events stream with their own
 * `agentId`s. Terminal `Completed` carries the pipeline's final
 * output (#1745). The channel / scope / context / terminal-event
 * lifecycle lives in the shared [agentSessionScope] (#2797). See
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
    // agentId for the terminal Completed: last agent's name (its OUT matches Pipeline's OUT).
    // Pipeline has no name of its own.
    return agentSessionScope({ pipeline.agents.lastOrNull()?.name ?: "pipeline" }) { emit ->
        pipeline.effectiveSessionExec(input, emit)
    }
}
