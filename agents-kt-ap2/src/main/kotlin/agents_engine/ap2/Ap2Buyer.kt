package agents_engine.ap2

import agents_engine.x402.X402Account
import agents_engine.x402.X402Client
import agents_engine.x402.X402SpendPolicy
import java.net.http.HttpClient

/**
 * `agents_engine/ap2/Ap2Buyer.kt` — AP2 (PRD §12.10) spike. The settlement bridge: given a verified Intent +
 * Cart mandate, pay the cart over **x402** — closing the AP2 loop *user-intent → cart → settlement* by
 * composing the three shipped pieces (mandate verify via [MandateVerifier], spend bounds via
 * [agents_engine.x402.X402SpendPolicy], payment via [X402Client]).
 *
 * **Two enforcement layers, both fed by the Intent Mandate:**
 * 1. AP2 layer — [MandateVerifier.reasonNotAuthorized] rejects a cart the intent didn't authorize, before any
 *    payment (→ [Ap2PaymentDeniedException]).
 * 2. x402 layer — [spendPolicyFrom] turns the *same* intent into an [X402SpendPolicy], so even a bypass at
 *    layer 1 cannot sign a payment outside the intent's cap / allowed merchants / network.
 *
 * The signing key stays in [X402Account] **below the model layer** (never in a prompt) — the AP2 negotiation
 * and the spend authorization are cleanly separated, exactly as the PRD requires.
 */
class Ap2Buyer(
    private val signerKeyHex: String,
    private val http: HttpClient = HttpClient.newHttpClient(),
) {
    /**
     * The Intent Mandate as an [X402SpendPolicy] — the "signed, verifiable spend policy" thesis made concrete:
     * the cap, allowed recipients, and network all derive from the cryptographically-verified intent.
     */
    fun spendPolicyFrom(intent: IntentMandate): X402SpendPolicy = X402SpendPolicy(
        maxValuePerPayment = intent.maxAmount,
        allowedPayTo = intent.allowedMerchants.toSet(),
        allowedNetworks = setOf(intent.network),
    )

    /**
     * Pay [cart] under the authority of [intent]. Verifies the AP2 authorization chain first (→
     * [Ap2PaymentDeniedException] if the cart isn't covered), then settles over x402 against the cart's
     * `resource` URL with a spend policy derived from the intent. Returns the [Ap2Receipt].
     */
    fun pay(intent: IntentMandate, cart: CartMandate): Ap2Receipt {
        MandateVerifier.reasonNotAuthorized(cart, intent)?.let {
            throw Ap2PaymentDeniedException("cart not authorized by intent: $it")
        }
        val account = X402Account.fromPrivateKey(signerKeyHex, spendPolicyFrom(intent))
        val response = X402Client(account, http).get(cart.resource)
        return Ap2Receipt(
            cartId = cart.cartId,
            status = response.statusCode(),
            body = response.body(),
            paymentResponse = response.headers().firstValue("X-PAYMENT-RESPONSE").orElse(null),
            payer = account.address,
        )
    }
}
