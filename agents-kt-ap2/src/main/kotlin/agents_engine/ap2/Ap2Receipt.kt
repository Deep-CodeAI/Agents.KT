package agents_engine.ap2

/**
 * `agents_engine/ap2/Ap2Receipt.kt` — AP2 (PRD §12.10) spike. The outcome of settling a Cart Mandate over
 * x402: the HTTP status the seller returned, its body, the base64 `X-PAYMENT-RESPONSE` settlement receipt (if
 * the seller settled), and the payer address that signed. Carries no key material.
 */
data class Ap2Receipt(
    val cartId: String,
    val status: Int,
    val body: String,
    val paymentResponse: String?,
    val payer: String,
) {
    /** True when the seller served the resource (HTTP 200) — i.e. payment verified + settled. */
    val settled: Boolean get() = status == HTTP_OK

    private companion object {
        const val HTTP_OK = 200
    }
}
