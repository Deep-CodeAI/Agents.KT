package agents_engine.core

import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.runBlocking

/**
 * `agents_engine/core/HumanGateRegistry.kt` — #3868. The discoverable
 * HITL surface over the shipped pause/resume primitives: run an agent
 * through [guard]; when a tool calls `humanApproval { }` (or
 * `interrupt(payload)`), the run parks as a [PendingGate] — list it,
 * show it to a reviewer, and `approve` / `reject` resumes exactly where
 * it left off (snapshot + manifest-hash restore guard, #2488/#2754).
 *
 * ```kotlin
 * val gates = HumanGateRegistry(snapshotStore = FileSnapshotStore(dir))
 * when (val run = gates.guard(checkout, order)) {
 *     is GateOutcome.Completed -> run.output
 *     is GateOutcome.Paused -> notifyReviewer(run.gate.gateId, run.gate.reason)
 * }
 * // later, out of band:
 * gates.find(gateId)?.approve(reviewer = "alice@acme.com", comment = "verified")
 * ```
 *
 * Approval transport is deliberately framework-agnostic: the programmatic
 * API above is what ships; webhook/email/Slack UIs call it. Audit events
 * ride the existing #2489 channel (`ApprovalRequested` fires at the
 * interrupt; `ApprovalDecided` fires on the resume path when the
 * decision is a [HumanDecision]).
 *
 * Snapshots are additionally persisted to [snapshotStore] (key =
 * `gate-<gateId>`) as crash evidence; resuming after a process restart
 * additionally needs the agent instance and input re-supplied — a
 * fully-rehydrating registry is a tracked follow-up on #3868.
 */
class HumanGateRegistry(
    private val snapshotStore: SnapshotStore? = null,
) {
    private val gates = ConcurrentHashMap<String, PendingGate<*>>()

    /**
     * Run [agent] on [input]; park as a [PendingGate] if it interrupts.
     * Blocking by design — gate decisions arrive out of band, often much
     * later, so the caller's thread is the agent's normal blocking entry.
     */
    fun <IN, OUT : Any> guard(agent: Agent<IN, OUT>, input: IN): GateOutcome<OUT> = try {
        GateOutcome.Completed(agent(input))
    } catch (e: AgentInterruptException) {
        val gateId = UUID.randomUUID().toString()
        snapshotStore?.save("gate-$gateId", e.snapshot)
        val gate = PendingGate<OUT>(
            gateId = gateId,
            agentName = agent.name,
            reason = (e.payload as? ApprovalRequest)?.title ?: e.payload?.toString() ?: "interrupted",
            payload = (e.payload as? ApprovalRequest)?.body ?: e.payload,
            requestedAt = Instant.now(),
            resume = { decision ->
                runBlocking { agent.invokeSuspendResuming(input, resumeFrom = e.snapshot, resumeWith = decision) }
            },
            onResolved = { id, _, _, _ -> gates.remove(id) },
        )
        gates[gateId] = gate
        GateOutcome.Paused(gate)
    }

    /** All unresolved gates, newest first. */
    fun pending(): List<PendingGate<*>> = gates.values.sortedByDescending { it.requestedAt }

    fun find(gateId: String): PendingGate<*>? = gates[gateId]
}
