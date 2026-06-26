package agents_engine.x402

import agents_engine.generation.LenientJsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * `agents_engine/x402/X402Client.kt` — #4528 (PRD §12.8). The **buyer side** of x402: drives the
 * `request → 402 → pay → retry` handshake so an agent can autonomously pay for a resource. Wraps a JDK
 * [HttpClient]; on a `402 Payment Required` it parses the seller's `accepts[]`, keeps every offer its
 * [X402Account] will pay (policy-permitted, supported scheme, known token domain), lets the [selector] choose
 * **which** to pay, signs an `X-PAYMENT` header, and replays the original request once.
 *
 * **The risk lives here**, so the guardrails are not optional theater: the [account]'s [X402SpendPolicy] is
 * consulted before any signature, the seller does not get to pick the offer (the [selector] does —
 * default [X402OfferSelector.LowestAmount]), signing stays in [X402Account] (below the model layer), and a
 * rejected payment raises [X402PaymentDeniedException] rather than silently overpaying. EXPERIMENTAL — real,
 * irreversible USDC moves when this is pointed at a live facilitator-backed seller.
 *
 * @property account the signing wallet + spend policy.
 * @property http the underlying client (inject one with a proxy/timeout to taste).
 * @property x402Version protocol version echoed into the `X-PAYMENT` payload.
 * @property selector chooses which policy-permitted offer to pay (default: lowest amount, not the seller's first).
 * @property sessionLimits optional cross-payment caps (count / total value / per-payee / cooldown) enforced
 *   against [spendStore]; null = no aggregate limits.
 * @property spendStore records settled payments for [sessionLimits] (default: per-process [InMemorySpendStore];
 *   use a durable store in production so a restart can't reset a cumulative cap).
 */
class X402Client(
    private val account: X402Account,
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS)).build(),
    private val x402Version: Int = 1,
    private val selector: X402OfferSelector = X402OfferSelector.LowestAmount,
    private val sessionLimits: X402SessionLimits? = null,
    private val spendStore: X402SpendStore = InMemorySpendStore(),
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
) {
    /** The buyer address that will pay (derived from the account's key). */
    val payerAddress: String get() = account.address

    /**
     * Send [request]; if the seller answers `402`, pay and retry once, returning the paid response (read its
     * `X-PAYMENT-RESPONSE` header for the settlement receipt). A non-`402` first response is returned
     * unchanged. A settled payment (HTTP 200) is recorded against the [spendStore] for [sessionLimits]. Throws
     * [X402PaymentDeniedException] when no offer is payable or a session limit is hit, and [X402Exception] if
     * the `402` body can't be parsed.
     */
    fun send(request: HttpRequest): HttpResponse<String> {
        val first = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (first.statusCode() != X402PaymentGate.HTTP_PAYMENT_REQUIRED) return first

        val payment = payFor(first.body())
        val paidRequest = HttpRequest.newBuilder(request) { _, _ -> true }
            .header("X-PAYMENT", payment.header)
            .build()
        val paid = http.send(paidRequest, HttpResponse.BodyHandlers.ofString())
        if (paid.statusCode() == HTTP_OK) {
            // settled — record for cross-payment limits
            spendStore.record(payment.authorization.to, payment.authorization.value, clockMillis())
        }
        return paid
    }

    /** Convenience: GET [uri], paying if challenged. */
    fun get(uri: String): HttpResponse<String> =
        send(HttpRequest.newBuilder().uri(URI.create(uri)).GET().build())

    /** Parse the `402` offers, keep the policy-permitted ones, let the selector choose, and sign — or explain. */
    private fun payFor(body: String): SignedPayment {
        val offers = parseAccepts(body)
        val reasons = mutableListOf<String>()
        val permitted = offers.filter { offer ->
            val reason = account.reasonCannotPay(offer)
            if (reason != null) reasons += "[${offer.network} ${offer.maxAmountRequired} -> ${offer.payTo}] $reason"
            reason == null
        }
        if (permitted.isEmpty()) {
            val detail = if (offers.isEmpty()) "no usable accepts[] offer" else reasons.joinToString("; ")
            throw X402PaymentDeniedException("no acceptable x402 offer (of ${offers.size}): $detail")
        }
        val chosen = selector.select(permitted)
            ?: throw X402PaymentDeniedException("offer selector declined all ${permitted.size} permitted offers")
        enforceSessionLimits(chosen)
        return account.authorize(chosen, x402Version)
    }

    /** Cross-payment caps (count / total / per-payee / cooldown) — checked before any signature. */
    private fun enforceSessionLimits(offer: PaymentRequirements) {
        sessionLimits?.reject(offer.payTo, offer.maxAmountRequired.toBigInteger(), spendStore, clockMillis())
            ?.let { throw X402PaymentDeniedException("session limit: $it") }
    }

    private fun parseAccepts(body: String): List<PaymentRequirements> {
        val root = LenientJsonParser.parse(body) as? Map<*, *>
            ?: throw X402Exception("402 body is not a JSON object")
        val accepts = root["accepts"] as? List<*> ?: return emptyList()
        return accepts.filterIsInstance<Map<*, *>>().mapNotNull { PaymentRequirements.fromJsonObject(it) }
    }

    private companion object {
        const val CONNECT_TIMEOUT_SECONDS = 10L
        const val HTTP_OK = 200
    }
}
