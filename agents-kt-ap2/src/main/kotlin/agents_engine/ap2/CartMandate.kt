package agents_engine.ap2

import agents_engine.agntcy.identity.VerifiedBadge
import java.math.BigInteger

/**
 * `agents_engine/ap2/CartMandate.kt` — AP2 (PRD §12.10) spike. The **Cart Mandate**: a *specific* finalized
 * cart, signed off immediately before payment, referencing the [IntentMandate] that authorized the shopping.
 * Also a JWS Verifiable Credential, read from a [VerifiedBadge]'s `credentialSubject`.
 *
 * The Cart Mandate is the natural **human-in-the-loop** gate — the last signed artifact before money moves.
 *
 * @property cartId unique id of this cart.
 * @property intentId the [IntentMandate.mandateId] this cart claims authorization under.
 * @property amount the cart total in the asset's atomic units.
 * @property payTo merchant recipient.
 * @property asset / @property network the settlement coordinates (must match the intent).
 * @property resource the URL to pay/fulfill (the seller endpoint that returns `402`).
 */
data class CartMandate(
    val cartId: String,
    val intentId: String,
    val amount: BigInteger,
    val payTo: String,
    val asset: String,
    val network: String,
    val resource: String,
) {
    companion object {
        /** Read a verified Cart-Mandate VC into the typed model; throws [Ap2MandateException] on a bad field. */
        fun fromVerified(badge: VerifiedBadge): CartMandate {
            val cs = badge.credentialSubject
            return CartMandate(
                cartId = cs.requireString("cartId"),
                intentId = cs.requireString("intentId"),
                amount = cs.requireString("amount").toBigIntegerOrNull()?.takeIf { it.signum() >= 0 }
                    ?: throw Ap2MandateException("cart.amount is not a non-negative integer"),
                payTo = cs.requireString("payTo"),
                asset = cs.requireString("asset"),
                network = cs.requireString("network"),
                resource = cs.requireString("resource"),
            )
        }
    }
}
