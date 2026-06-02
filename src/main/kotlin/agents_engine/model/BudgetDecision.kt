package agents_engine.model

/**
 * Returned by [agents_engine.core.Agent.onBudgetExceeded] when a budget cap is
 * about to throw (#2412). The handler can let it stop, or raise the limit and
 * continue — e.g. "ActorsAgent hit 32 tool calls but we need to keep going."
 *
 * #2412 wired this for [BudgetReason.TOOL_CALLS] only. #2750 broadened
 * coverage to [BudgetReason.TURNS], [BudgetReason.DURATION],
 * [BudgetReason.TOKENS], and [BudgetReason.CONSECUTIVE_TOOL]. The handler now
 * fires consistently at every cumulative-cap throw site. [BudgetReason
 * .PER_TOOL_TIMEOUT] remains unconditionally throwing because extending
 * mid-tool needs interrupt semantics (separate ticket).
 *
 * Units for [Extend.newLimit] by reason:
 * - [BudgetReason.TOOL_CALLS] / [BudgetReason.TURNS] / [BudgetReason.TOKENS]
 *   / [BudgetReason.CONSECUTIVE_TOOL] → integer count
 * - [BudgetReason.DURATION] → milliseconds
 */
sealed interface BudgetDecision {
    /** Throw [BudgetExceededException] — the default when no handler is registered. */
    object Stop : BudgetDecision

    /**
     * Raise the limit for the current [BudgetReason] to [newLimit] and continue.
     * Ignored (falls back to [Stop]) unless [newLimit] exceeds the current limit.
     */
    data class Extend(val newLimit: Int) : BudgetDecision

    /**
     * #2749 — pause the agentic loop at the current turn boundary. The runtime
     * captures a [agents_engine.core.SessionSnapshot] of the in-flight state,
     * delivers it to the caller via the registered `onTurnCheckpoint` hook,
     * and throws a recoverable [BudgetCheckpointException] that carries the
     * same snapshot on its own field.
     *
     * The caller (typically an HTTP handler / chat-app dialog controller)
     * catches the exception, surfaces a "raise the cap and continue?" prompt
     * to the human, and on acceptance resumes via
     * `agent.invokeSuspendResuming(input, resumeFrom = snapshot)` with the
     * budget config updated to a larger cap. The conversation history is
     * preserved end-to-end — no replay tax.
     *
     * Falls back to [Stop] semantics (throws [BudgetExceededException])
     * when no `onTurnCheckpoint` is registered on the invocation —
     * Checkpoint without a place to land the snapshot is no different from
     * Stop, and silently swallowing the budget breach would be worse.
     *
     * #2764 — Checkpoint coverage now matches [Extend]: it fires at every
     * cumulative-cap throw site (TOOL_CALLS / TURNS / DURATION / TOKENS /
     * CONSECUTIVE_TOOL). [BudgetReason.PER_TOOL_TIMEOUT] remains
     * unconditionally throwing (extending a single in-flight tool needs
     * interrupt semantics — separate ticket).
     */
    object Checkpoint : BudgetDecision
}
