package agents_engine.observability

/**
 * The decision recorded for an action in the [ToolAuditLedger].
 *
 * #2886 seeded the tool-action verdicts ([APPROVED] / [DENIED] / [HALLUCINATED]).
 * #2905 folds in the cross-cutting *agent-misbehaviour* signals that never flow
 * through a tool body — [BUDGET_EXCEEDED] (a budget ceiling crossed) and
 * [INFRA_ERROR] (a transport/parse/runtime failure surfaced via `onError`) — so the
 * one tamper-evident Merkle chain answers "what did agents try to do that they
 * shouldn't, and what went wrong." Each carries its [severity] and [isMisbehaviour]
 * as a fixed function of the verdict, so no new persisted column is needed.
 */
enum class LedgerDecision {
    APPROVED,
    DENIED,
    HALLUCINATED,
    BUDGET_EXCEEDED,
    INFRA_ERROR,
    ;

    /** True for every verdict except an authorized [APPROVED] tool call. */
    val isMisbehaviour: Boolean get() = this != APPROVED

    /** Triage level for this verdict — see [LedgerSeverity]. */
    val severity: LedgerSeverity
        get() = when (this) {
            APPROVED -> LedgerSeverity.INFO
            HALLUCINATED, BUDGET_EXCEEDED, INFRA_ERROR -> LedgerSeverity.WARN
            DENIED -> LedgerSeverity.CRITICAL
        }
}
