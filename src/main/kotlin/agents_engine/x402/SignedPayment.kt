package agents_engine.x402

/**
 * `agents_engine/x402/SignedPayment.kt` — #4528 (PRD §12.8). The product of [X402Account.authorize]: the
 * signed [PaymentAuthorization], its `0x…` EIP-712 signature, and the base64-encoded `X-PAYMENT` [header] an
 * [X402Client] attaches when it replays a `402`-challenged request.
 */
internal data class SignedPayment(
    val authorization: PaymentAuthorization,
    val signature: String,
    val header: String,
)
