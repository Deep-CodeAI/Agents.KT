package agents_engine.ap2

import agents_engine.agntcy.identity.VerifiedBadge
import java.math.BigInteger
import java.time.Instant

/**
 * `agents_engine/ap2/IntentMandate.kt` — AP2 (PRD §12.10) spike. The **Intent Mandate**: a user authorizes an
 * agent to *shop* within bounds ("running shoes, ≤ \$150, on this network, to these merchants, until X"). It is
 * a signed [W3C Verifiable Credential](https://www.w3.org/TR/vc-data-model/) — mechanically the *same* JWS VC
 * the AGNTCY Identity module already verifies, so it is read out of a [VerifiedBadge]'s `credentialSubject`
 * (the VC claim) + the JWT `exp` (already enforced by the verifier).
 *
 * Conceptually this is a **scoped, signed, verifiable [agents_engine.x402.X402SpendPolicy]** — the spend bound
 * travels with cryptographic proof of the user's intent, rather than living in an in-process policy object. See
 * [Ap2Buyer.spendPolicyFrom].
 *
 * @property mandateId stable id a [CartMandate] references back to (`intentId`).
 * @property holder the authorized agent/subject (JWT `sub`).
 * @property maxAmount spend ceiling in the asset's atomic units (USDC = 6 decimals).
 * @property asset token contract the spend is denominated in.
 * @property network settlement network the spend is bound to.
 * @property allowedMerchants recipient allowlist the agent may pay (the `payTo`s it may authorize).
 * @property expiresAt the mandate's expiry (JWT `exp`); past this, no cart it backs is payable.
 */
data class IntentMandate(
    val mandateId: String,
    val holder: String?,
    val maxAmount: BigInteger,
    val asset: String,
    val network: String,
    val allowedMerchants: List<String>,
    val expiresAt: Instant?,
) {
    companion object {
        /**
         * Read a verified Intent-Mandate VC into the typed model. Throws [Ap2MandateException] if a required
         * `credentialSubject` field is missing/malformed — a mandate the spend layer must not act on.
         */
        fun fromVerified(badge: VerifiedBadge): IntentMandate {
            val cs = badge.credentialSubject
            return IntentMandate(
                mandateId = cs.requireString("mandateId"),
                holder = badge.subject,
                maxAmount = cs.requireString("maxAmount").toBigIntegerOrNull()?.takeIf { it.signum() >= 0 }
                    ?: throw Ap2MandateException("intent.maxAmount is not a non-negative integer"),
                asset = cs.requireString("asset"),
                network = cs.requireString("network"),
                allowedMerchants = (cs["allowedMerchants"] as? List<*>)?.filterIsInstance<String>()
                    ?: throw Ap2MandateException("intent.allowedMerchants is missing or not a list"),
                expiresAt = badge.expiresAt,
            )
        }
    }
}

internal fun Map<String, Any?>.requireString(key: String): String =
    this[key] as? String ?: throw Ap2MandateException("mandate is missing required field '$key'")
