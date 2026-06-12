package agents_engine.composition.branch

import agents_engine.core.Agent

/**
 * `agents_engine/composition/branch/Handoff.kt` — #3871. The named
 * hand-off operator: a triage/router agent transfers control to a
 * specialist based on its typed output.
 *
 * ```kotlin
 * val flow = triage handoff {
 *     on<BillingTask>() then billing
 *     on<TechTask>()    then tech
 * }
 * flow("my card was double-charged")   // Branch<UserMessage, Resolution>
 * ```
 *
 * Same routing semantics and builder as [branch] — `handoff` is the
 * keyword with an audit contract on top: route selection fires the
 * source agent's `onHandoff` listener and `PipelineEvent.HandoffPerformed`
 * (via `observe { }`), so reviewers can grep transfers specifically.
 *
 * **Unlike OpenAI-Swarm-style handoff, the target does NOT share or
 * mutate the source's conversation history.** Each target receives only
 * its declared input type (the source's routed output) — the
 * single-placement rule and typed boundaries hold across the transfer.
 * Sealed source outputs get the same construction-time exhaustiveness
 * validation as `branch`.
 */
infix fun <IN, SEALED : Any, OUT> Agent<IN, SEALED>.handoff(
    block: BranchBuilder<OUT>.() -> Unit,
): Branch<IN, OUT> {
    val builder = BranchBuilder<OUT>()
    builder.block()
    validateSealedCompleteness(this.outType, builder.routes)
    return Branch(this, builder.routes, isHandoff = true)
}
