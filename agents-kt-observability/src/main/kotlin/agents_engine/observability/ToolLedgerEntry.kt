package agents_engine.observability

/**
 * One tamper-evident row in a [ToolAuditLedger]. [entryHash] chains to [prevHash]
 * (the previous row's hash; the genesis row links to [ToolAuditLedger.GENESIS_HASH]),
 * so any edit / insert / delete / reorder is detectable by recomputation. PII-safe:
 * [resultHash] is the SHA-256 of the tool result, never the result itself.
 */
data class ToolLedgerEntry(
    val sequence: Long,
    val timestamp: String,
    val callId: String?,
    val toolName: String,
    val decision: String,
    val denialReason: String?,
    val resultHash: String?,
    val prevHash: String,
    val entryHash: String,
)

/**
 * The persisted [decision] string parsed back to a [LedgerDecision], or `null` for a
 * verdict written by a newer version than this reader knows (forward-compatible — an
 * unknown verdict never throws when reading an audit file).
 */
val ToolLedgerEntry.decisionType: LedgerDecision?
    get() = LedgerDecision.entries.firstOrNull { it.name == decision }

/**
 * #2905 — true when this row records agent misbehaviour (a denial, hallucinated call,
 * budget breach, or infra error) rather than an authorized [LedgerDecision.APPROVED]
 * action. An unrecognised verdict is treated as misbehaviour (fail-safe: surface it).
 */
val ToolLedgerEntry.isMisbehaviour: Boolean
    get() = decisionType?.isMisbehaviour ?: true

/** Triage level of this row, derived from its [decisionType] (unknown verdicts → [LedgerSeverity.WARN]). */
val ToolLedgerEntry.severity: LedgerSeverity
    get() = decisionType?.severity ?: LedgerSeverity.WARN
