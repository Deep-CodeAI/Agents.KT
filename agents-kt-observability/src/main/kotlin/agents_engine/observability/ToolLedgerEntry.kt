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
