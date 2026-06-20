package agents_engine.x402

import java.math.BigInteger

/**
 * `agents_engine/x402/PaymentPlan.kt` — #4528 (PRD §12.8). The human-readable summary of a payment about to be
 * authorized — what an [X402SpendPolicy.confirm] human-in-the-loop gate is shown, and what a denial reason
 * references. Carries no key material; safe to log or surface to a user.
 */
data class PaymentPlan(
    val network: String,
    val payTo: String,
    val value: BigInteger,
    val asset: String,
    val resource: String,
)
