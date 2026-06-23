package agents_engine.ap2

import agents_engine.agntcy.identity.IdentityVerifier
import com.nimbusds.jose.jwk.JWKSet

/**
 * `agents_engine/ap2/MandateVerifier.kt` — AP2 (PRD §12.10) spike. Verifies AP2 mandates by **reusing the
 * shipped [IdentityVerifier]** (epic #4517) — a mandate is a JWS Verifiable Credential, the exact artifact that
 * verifier was built for. So the whole forgery-resistance surface (fail-closed; rejects `alg:none`, `HS*`
 * algorithm-confusion, expiry, tamper, unknown/wrong key) comes for free; this layer adds only the typed
 * mandate model and the **authorization chain** check.
 *
 * The chain is the load-bearing AP2 rule: a Cart Mandate is payable only if its Intent Mandate actually
 * authorized *this* spend — same intent id, within the cap, to an allowed merchant, on the matching
 * asset/network. This is enforced *before* the x402 layer (which re-enforces it via a derived spend policy).
 */
object MandateVerifier {
    /** Verify an Intent-Mandate VC-JWT against [jwks] and return the typed mandate. */
    fun verifyIntent(compactJws: String, jwks: JWKSet): IntentMandate =
        IntentMandate.fromVerified(IdentityVerifier.verify(compactJws, jwks))

    /** Verify a Cart-Mandate VC-JWT against [jwks] and return the typed mandate. */
    fun verifyCart(compactJws: String, jwks: JWKSet): CartMandate =
        CartMandate.fromVerified(IdentityVerifier.verify(compactJws, jwks))

    /**
     * Why [cart] is NOT authorized by [intent], or null if it is. Pure check over already-verified mandates —
     * no crypto here (the signatures were validated by [verifyIntent]/[verifyCart]).
     */
    fun reasonNotAuthorized(cart: CartMandate, intent: IntentMandate): String? = when {
        cart.intentId != intent.mandateId ->
            "cart.intentId '${cart.intentId}' does not match the intent '${intent.mandateId}'"
        cart.amount > intent.maxAmount ->
            "cart amount ${cart.amount} exceeds the intent cap ${intent.maxAmount}"
        !cart.asset.equals(intent.asset, ignoreCase = true) ->
            "cart asset '${cart.asset}' != intent asset '${intent.asset}'"
        !cart.network.equals(intent.network, ignoreCase = true) ->
            "cart network '${cart.network}' != intent network '${intent.network}'"
        intent.allowedMerchants.none { it.equals(cart.payTo, ignoreCase = true) } ->
            "cart payTo '${cart.payTo}' is not in the intent's allowed merchants"
        else -> null
    }
}
