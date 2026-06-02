package agents_engine.observability

/** Result of [ToolAuditLedger.verify]. [brokenAtSequence] is the first tampered row, if any. */
data class LedgerVerification(
    val ok: Boolean,
    val brokenAtSequence: Long? = null,
    val reason: String? = null,
)
