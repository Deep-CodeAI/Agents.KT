package agents_engine.core

import java.time.Instant

/**
 * #3868 — one paused agent run awaiting a human decision. Created by
 * [HumanGateRegistry.guard] when the run interrupts (via
 * `humanApproval { }` or `interrupt(payload)`); resolved exactly once by
 * [approve] / [reject] / [resolve], which resumes the run from its
 * snapshot and returns the final output. The snapshot's manifest-hash
 * restore guard applies on resume — a changed agent shape fails closed.
 */
class PendingGate<OUT> internal constructor(
    val gateId: String,
    val agentName: String,
    /** The `ApprovalRequest.title` (humanApproval path) or the interrupt payload's string form. */
    val reason: String,
    /** The interrupt payload / approval body, for the reviewer's eyes. */
    val payload: Any?,
    val requestedAt: Instant,
    private val resume: (HumanDecision) -> OUT,
    private val onResolved: (gateId: String, decision: String, reviewer: String, comment: String) -> Unit,
) {
    @Volatile
    var resolved: Boolean = false
        private set

    /** Approve and resume; returns the run's final output. */
    fun approve(reviewer: String, comment: String = ""): OUT =
        resolve(HumanDecision.Approved, reviewer, comment)

    /** Reject and resume (the agent sees the rejection as the tool result — fail-closed is its job). */
    fun reject(reviewer: String, comment: String = ""): OUT =
        resolve(HumanDecision.Rejected, reviewer, comment)

    /** Resume with any [HumanDecision] (Edited / Responded carry a payload). */
    fun resolve(decision: HumanDecision, reviewer: String, comment: String = ""): OUT {
        check(!resolved) { "Gate $gateId is already resolved." }
        resolved = true
        onResolved(gateId, decision::class.simpleName ?: "Decision", reviewer, comment)
        return resume(decision)
    }
}
