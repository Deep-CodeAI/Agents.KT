package agents_engine.x402

/**
 * `agents_engine/x402/FacilitatorVerification.kt` — #4527 (PRD §12.8). Result of a facilitator `/verify`:
 * whether a buyer's signed payment payload is valid for the seller's [PaymentRequirements], without yet
 * moving money. The facilitator (not the seller) does the EIP-712/EIP-3009 cryptographic checks.
 */
data class FacilitatorVerification(
    val isValid: Boolean,
    val invalidReason: String? = null,
    val payer: String? = null,
)
