package agents_engine.x402

/**
 * `agents_engine/x402/X402Exception.kt` — #4527 (PRD §12.8). A facilitator/transport failure during x402
 * payment verification or settlement. [X402PaymentGate] catches it and **fails closed** (responds `402`,
 * never serves the protected resource) — money handling defaults to deny.
 */
class X402Exception(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
