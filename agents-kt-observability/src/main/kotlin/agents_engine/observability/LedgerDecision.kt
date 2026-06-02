package agents_engine.observability

/** The decision recorded for a tool action in the [ToolAuditLedger]. */
enum class LedgerDecision { APPROVED, DENIED, HALLUCINATED }
