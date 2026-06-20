package agents_engine.x402

import java.math.BigInteger

/**
 * `agents_engine/x402/X402SpendPolicy.kt` — #4528 (PRD §12.8). The buyer-side **spend guardrails**. x402 moves
 * **irreversible** money and the canonical failure mode is a prompt-injected agent draining a wallet
 * (Grok/Bankr ≈ \$150–200k, Freysa \$47k are confirmed-real), so an [X402Account] refuses to sign a payment
 * that this policy doesn't permit. The policy lives **below the model layer** — it is configured in code by
 * the operator, never carried in a prompt, and the LLM cannot widen it.
 *
 * Every limit is opt-in but composes: a payment must satisfy *all* set constraints.
 *
 * @property maxValuePerPayment hard per-payment cap in the token's atomic units (USDC = 6 decimals); null = no
 *   cap. The single most important guardrail — bound the blast radius of one signed authorization.
 * @property allowedNetworks settlement networks the buyer will pay on (e.g. `"base"`); empty = any network.
 * @property allowedPayTo recipient address allowlist (case-insensitive); empty = any recipient. Pin this to
 *   known sellers to neutralize a redirected-`payTo` injection.
 * @property confirm human-in-the-loop gate, run after the static checks pass: return false to veto. null =
 *   auto-approve within the static limits. Wire this to a real prompt for high-value or untrusted flows.
 */
class X402SpendPolicy(
    val maxValuePerPayment: BigInteger? = null,
    val allowedNetworks: Set<String> = emptySet(),
    val allowedPayTo: Set<String> = emptySet(),
    val confirm: ((PaymentPlan) -> Boolean)? = null,
) {
    /**
     * Approve or reject [plan]. Returns null when permitted; otherwise a human-readable reason the payment was
     * refused (so the caller can surface *why* nothing was paid). Static limits are checked before [confirm]
     * runs, so an HITL prompt only ever sees an already-bounded payment.
     */
    internal fun reject(plan: PaymentPlan): String? {
        val value = plan.value
        if (maxValuePerPayment != null && value > maxValuePerPayment) {
            return "amount ${plan.value} exceeds maxValuePerPayment $maxValuePerPayment"
        }
        if (allowedNetworks.isNotEmpty() && plan.network !in allowedNetworks) {
            return "network '${plan.network}' is not in the allowed set $allowedNetworks"
        }
        if (allowedPayTo.isNotEmpty() && plan.payTo.lowercase() !in allowedPayTo.map { it.lowercase() }.toSet()) {
            return "payTo '${plan.payTo}' is not in the allowed recipients"
        }
        if (confirm != null && !confirm.invoke(plan)) {
            return "payment was declined by the confirm() gate"
        }
        return null
    }
}
