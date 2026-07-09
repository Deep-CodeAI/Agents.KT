package agents_engine.x402

import java.math.BigInteger
import java.net.URI

/**
 * `agents_engine/x402/X402SpendPolicy.kt` — #4528 (PRD §12.8). The buyer-side **spend guardrails**. x402 moves
 * **irreversible** money and the canonical failure mode is a prompt-injected agent draining a wallet
 * (Grok/Bankr ≈ \$150–200k, Freysa \$47k are confirmed-real), so an [X402Account] refuses to sign a payment
 * that this policy doesn't permit. The policy lives **below the model layer** — it is configured in code by
 * the operator, never carried in a prompt, and the LLM cannot widen it.
 *
 * **A policy is mandatory** (`X402Account.fromPrivateKey` requires one). An empty allow-set means "no
 * restriction on that dimension", so the all-empty policy is unrestricted — to make that posture impossible to
 * reach by accident, the only way to build it is the explicitly-named [unsafeAllowAllForTesting].
 *
 * Every limit is opt-in but composes: a payment must satisfy *all* set constraints. A policy-approved
 * recipient does **not** automatically mean any token, any URL, or any authorization duration — bind those
 * with [allowedAssets] / [allowedResourceOrigins] / [maxAuthorizationLifetimeSeconds].
 *
 * @property maxValuePerPayment hard per-payment cap in the token's atomic units (USDC = 6 decimals); null = no
 *   cap. The single most important guardrail — bound the blast radius of one signed authorization.
 * @property allowedNetworks settlement networks the buyer will pay on (e.g. `"base"`); empty = any network.
 * @property allowedPayTo recipient address allowlist (case-insensitive); empty = any recipient. Pin this to
 *   known sellers to neutralize a redirected-`payTo` injection.
 * @property allowedAssets token-contract allowlist (case-insensitive); empty = any token. Pin this so an
 *   approved recipient cannot be paid in an unexpected (e.g. worthless or malicious) token.
 * @property allowedResourceOrigins `scheme://host[:port]` origins of the paid resource URL; empty = any. Pin
 *   this so a payment can only be made to a known endpoint, not an arbitrary attacker-supplied URL.
 * @property maxAuthorizationLifetimeSeconds cap on how long a signed authorization stays valid; null = accept
 *   the seller's `maxTimeoutSeconds` as-is. [X402Account] clamps the signed `validBefore` to this cap, so a
 *   seller cannot mint a long-lived authorization against the buyer's key.
 * @property confirm human-in-the-loop gate, run after the static checks pass: return false to veto. null =
 *   auto-approve within the static limits. Wire this to a real prompt for high-value or untrusted flows.
 */
class X402SpendPolicy(
    val maxValuePerPayment: BigInteger? = null,
    val allowedNetworks: Set<String> = emptySet(),
    val allowedPayTo: Set<String> = emptySet(),
    val allowedAssets: Set<String> = emptySet(),
    val allowedResourceOrigins: Set<String> = emptySet(),
    val maxAuthorizationLifetimeSeconds: Long? = null,
    val confirm: ((PaymentPlan) -> Boolean)? = null,
) {
    /**
     * Approve or reject [plan]. Returns null when permitted; otherwise a human-readable reason the payment was
     * refused (so the caller can surface *why* nothing was paid). Static limits are checked before [confirm]
     * runs, so an HITL prompt only ever sees an already-bounded payment. (The authorization-lifetime cap is
     * enforced separately by [X402Account] at signing time, since the plan carries no lifetime.)
     */
    internal fun reject(plan: PaymentPlan): String? {
        if (maxValuePerPayment != null && plan.value > maxValuePerPayment) {
            return "amount ${plan.value} exceeds maxValuePerPayment $maxValuePerPayment"
        }
        if (allowedNetworks.isNotEmpty() && plan.network !in allowedNetworks) {
            return "network '${plan.network}' is not in the allowed set $allowedNetworks"
        }
        if (allowedPayTo.isNotEmpty() && !containsIgnoreCase(allowedPayTo, plan.payTo)) {
            return "payTo '${plan.payTo}' is not in the allowed recipients"
        }
        if (allowedAssets.isNotEmpty() && !containsIgnoreCase(allowedAssets, plan.asset)) {
            return "asset '${plan.asset}' is not in the allowed assets"
        }
        if (allowedResourceOrigins.isNotEmpty()) {
            val origin = originOf(plan.resource)
            if (origin == null || !containsIgnoreCase(allowedResourceOrigins, origin)) {
                return "resource origin of '${plan.resource}' is not in the allowed origins $allowedResourceOrigins"
            }
        }
        if (confirm != null && !confirm.invoke(plan)) {
            return "payment was declined by the confirm() gate"
        }
        return null
    }

    private fun containsIgnoreCase(set: Set<String>, value: String): Boolean =
        set.any { it.equals(value, ignoreCase = true) }

    private fun originOf(url: String): String? = runCatching {
        val u = URI(url)
        val scheme = u.scheme ?: return null
        val host = u.host ?: return null
        "$scheme://$host${if (u.port == -1) "" else ":${u.port}"}"
    }.getOrNull()

    companion object {
        /**
         * The all-permissive policy (no caps, no allowlists, no HITL). Named to make the unsafe posture an
         * explicit, greppable choice — use only in tests or when you genuinely intend an unbounded wallet.
         */
        fun unsafeAllowAllForTesting(): X402SpendPolicy = X402SpendPolicy()
    }
}
