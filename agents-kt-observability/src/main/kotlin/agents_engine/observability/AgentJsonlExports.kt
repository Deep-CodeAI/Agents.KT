package agents_engine.observability

import agents_engine.core.Agent
import agents_engine.core.PipelineEvent
import agents_engine.core.observe
import java.io.File

val <IN, OUT> Agent<IN, OUT>.events: AgentJsonlExports
    get() = AgentJsonlExports(this)

class AgentJsonlExports internal constructor(private val agent: Agent<*, *>) {
    fun export(block: AgentJsonlExportBuilder.() -> Unit): List<JsonlAuditExporter> {
        val builder = AgentJsonlExportBuilder(agent)
        builder.block()
        return builder.exporters.toList()
    }

    /**
     * #2886 / #2905 — wire a tamper-evident [ToolAuditLedger] to this agent. Tool actions
     * AND cross-cutting agent-misbehaviour are auto-recorded to one append-only,
     * Merkle-chained file, so the same chain answers "what did agents try to do that they
     * shouldn't, and what went wrong":
     * - [PipelineEvent.ToolCalled] → `APPROVED`
     * - [PipelineEvent.ToolDenied] → `DENIED` (policy/interceptor block, with the reason)
     * - [PipelineEvent.ToolHallucinated] → `HALLUCINATED` (tool outside the skill allowlist)
     * - [PipelineEvent.BudgetThreshold] → `BUDGET_EXCEEDED` (a budget ceiling crossed) — #2905
     * - [PipelineEvent.ErrorOccurred] → `INFRA_ERROR` (a failure surfaced via `onError`) — #2905
     *
     * PII-safe throughout: the tool result is hashed, never stored, and an error is recorded
     * by its exception *class* only — the message (which may carry secrets) stays out of the
     * row. Read the misbehaviour rows back with [ToolAuditLedger.readMisbehaviour]. Returns the
     * ledger so the caller can [ToolAuditLedger.verify] it later.
     *
     * The ledger writer stays unreachable through `ToolEnvironment` (#2883) — it only ever
     * observes framework events, so a compromised tool cannot forge or rewrite its own row.
     *
     * callId-keying of the non-tool rows lands once `PipelineEvent` carries the callId (the
     * approved rows already join via the AgentEvent layer) — #2886 follow-up.
     */
    fun ledger(file: File): ToolAuditLedger {
        val ledger = ToolAuditLedger(file.toPath())
        agent.observe { event ->
            when (event) {
                is PipelineEvent.ToolCalled ->
                    ledger.record(event.toolName, LedgerDecision.APPROVED, result = event.result)
                is PipelineEvent.ToolDenied ->
                    ledger.record(event.toolName, LedgerDecision.DENIED, denialReason = event.reason)
                is PipelineEvent.ToolHallucinated ->
                    ledger.record(event.requestedName, LedgerDecision.HALLUCINATED)
                is PipelineEvent.BudgetThreshold ->
                    ledger.record(
                        event.reason.name,
                        LedgerDecision.BUDGET_EXCEEDED,
                        denialReason = "${event.reason.name} budget at ${event.usedPercent} of limit",
                    )
                is PipelineEvent.ErrorOccurred ->
                    ledger.record(
                        event.error::class.simpleName ?: "Throwable",
                        LedgerDecision.INFRA_ERROR,
                        denialReason = event.error::class.qualifiedName, // class only — message may carry PII
                    )
                else -> Unit
            }
        }
        return ledger
    }
}
