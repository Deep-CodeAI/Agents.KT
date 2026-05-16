package agents_engine.composition.wrap

import agents_engine.composition.pipeline.Pipeline
import agents_engine.core.Agent

/**
 * `agents_engine/composition/wrap/Wrap.kt` — the teacher-student
 * prompt-override operator (#1698). `teacher wrap student` returns a
 * `Pipeline` where the teacher's output (a `String`) becomes the
 * student's system prompt for that one call. Two framings: education
 * (a teacher specializes a generalist student) and security (the
 * teacher locks down the student's task surface). Race-safe — the
 * teacher's prompt is passed via `effectivePrompt` instead of
 * mutating `agent.prompt` (#1707). See
 * `src/main/resources/internals-agent/composition/wrap/Wrap.md`
 * (#1837 / #1875).
 */

/**
 * Teacher-student prompt-override operator (#1698).
 *
 * `teacher wrap student` returns a [Pipeline] that:
 *
 * 1. Runs the teacher with the input, producing a `String`.
 * 2. Runs the student with the same input, using the teacher's string as the
 *    system prompt for that one call.
 *
 * The student's baked-in prompt is restored after the call. The PRD calls
 * this the "`>>` operator"; Kotlin doesn't permit user types to overload
 * the literal `>>` symbol, so the function is named `wrap` (matching the
 * PRD's "wrap operator" wording).
 *
 * Two framings:
 *
 * - **Education** — the teacher specializes a generalist student. One
 *   delegate agent can be reused across many narrow jobs because the
 *   teacher hands it the task-specific context.
 * - **Security** — the teacher locks down the student's task surface for
 *   the call. The student's default prompt does not apply, so it cannot
 *   drift to a wider task than the teacher allows.
 *
 * Both agents must share the same input type — the same input flows to
 * both, with the teacher producing the system prompt and the student
 * producing the actual output.
 *
 * The returned [Pipeline] participates in the single-placement contract:
 * either agent can be placed in at most one structure.
 *
 * ```kotlin
 * val supervisor = agent<String, String>("supervisor") { ... }   // emits a prompt
 * val worker = agent<String, Result>("worker") { ... }           // does the work
 *
 * val constrained: Pipeline<String, Result> = supervisor wrap worker
 * val result = constrained("user request")
 * ```
 */
infix fun <IN, OUT> Agent<IN, String>.wrap(student: Agent<IN, OUT>): Pipeline<IN, OUT> {
    this.markPlaced("pipeline")
    student.markPlaced("pipeline")
    val teacher = this
    return Pipeline(
        agents = listOf(teacher, student),
        // #1747: streaming path — teacher streams its events, then its
        // typed `String` output becomes the student's prompt override
        // for the wrapped run. Both agents' events flow with their own
        // agentIds.
        sessionExec = { input, emitter ->
            val (override, _) = agents_engine.runtime.events.runAgentInSession(teacher, input, emitter)
            val (out, _) = agents_engine.runtime.events.runAgentInSession(
                student, input, emitter,
                promptOverride = override,
            )
            out
        },
        execution = { input ->
            val promptOverride = teacher.invokeSuspend(input)
            student.invokeSuspendWithPromptOverride(input, promptOverride)
        },
    )
}
