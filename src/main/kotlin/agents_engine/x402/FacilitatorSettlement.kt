package agents_engine.x402

/**
 * `agents_engine/x402/FacilitatorSettlement.kt` — #4527 (PRD §12.8). Result of a facilitator `/settle`: the
 * facilitator submitted the buyer's EIP-3009 authorization on-chain. [transaction] is the settled tx hash.
 * The seller surfaces this back to the buyer as the `X-PAYMENT-RESPONSE` header.
 */
data class FacilitatorSettlement(
    val success: Boolean,
    val transaction: String? = null,
    val network: String? = null,
    val payer: String? = null,
    val errorReason: String? = null,
)
