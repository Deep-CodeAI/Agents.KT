package agents_engine.core

/**
 * The sealed result of a human approval request. Caller passes one of
 * these as `resumeWith` to `invokeSuspendResuming(...)`:
 *
 * - [Approved] — proceed.
 * - [Rejected] — refuse. Sensitive actions should fail-closed.
 * - [Edited] — the human modified the plan; `payload` carries the new
 *   plan (typically the same type as the original `body`).
 * - [Responded] — the human gave a free-form reply (e.g. "first ask
 *   the user for clarification on X"); `payload` is the reply.
 */
sealed interface HumanDecision {
    object Approved : HumanDecision
    object Rejected : HumanDecision
    data class Edited(val payload: Any?) : HumanDecision
    data class Responded(val payload: Any?) : HumanDecision
}
