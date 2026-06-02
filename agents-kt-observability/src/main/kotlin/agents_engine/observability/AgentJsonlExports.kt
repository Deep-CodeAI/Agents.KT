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
     * #2886 — wire a tamper-evident [ToolAuditLedger] to this agent. Every tool action is
     * auto-recorded to an append-only, Merkle-chained file: a [PipelineEvent.ToolCalled] as
     * `APPROVED`, a [PipelineEvent.ToolDenied] as `DENIED` (with the reason), a
     * [PipelineEvent.ToolHallucinated] as `HALLUCINATED`. PII-safe (the result is hashed,
     * never stored). Returns the ledger so the caller can [ToolAuditLedger.verify] it later.
     *
     * callId-keying of the denied/hallucinated rows lands once `PipelineEvent` carries the
     * callId (the approved rows already join via the AgentEvent layer) — #2886 follow-up.
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
                else -> Unit
            }
        }
        return ledger
    }
}
