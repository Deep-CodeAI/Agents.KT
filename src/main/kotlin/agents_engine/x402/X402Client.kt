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
 * [HttpClient]; on a `402 Payment Required` it parses the seller's `accepts[]`, picks the first offer its
 * [X402Account] will pay (policy-permitted, supported scheme, known token domain), signs an `X-PAYMENT`
 * header, and replays the original request once.
 *
 * **The risk lives here**, so the guardrails are not optional theater: the [account]'s [X402SpendPolicy] is
 * consulted before any signature, signing stays in [X402Account] (below the model layer), and a rejected
 * payment raises [X402PaymentDeniedException] rather than silently overpaying. EXPERIMENTAL — real,
 * irreversible USDC moves when this is pointed at a live facilitator-backed seller.
 *
 * @property account the signing wallet + spend policy.
 * @property http the underlying client (inject one with a proxy/timeout to taste).
 * @property x402Version protocol version echoed into the `X-PAYMENT` payload.
 */
class X402Client(
    private val account: X402Account,
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS)).build(),
    private val x402Version: Int = 1,
) {
    /** The buyer address that will pay (derived from the account's key). */
    val payerAddress: String get() = account.address

    /**
     * Send [request]; if the seller answers `402`, pay and retry once, returning the paid response (read its
     * `X-PAYMENT-RESPONSE` header for the settlement receipt). A non-`402` first response is returned
     * unchanged. Throws [X402PaymentDeniedException] when no offer is payable and [X402Exception] if the `402`
     * body can't be parsed.
     */
    fun send(request: HttpRequest): HttpResponse<String> {
        val first = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (first.statusCode() != X402PaymentGate.HTTP_PAYMENT_REQUIRED) return first

        val payment = payFor(first.body())
        val paidRequest = HttpRequest.newBuilder(request) { _, _ -> true }
            .header("X-PAYMENT", payment.header)
            .build()
        return http.send(paidRequest, HttpResponse.BodyHandlers.ofString())
    }

    /** Convenience: GET [uri], paying if challenged. */
    fun get(uri: String): HttpResponse<String> =
        send(HttpRequest.newBuilder().uri(URI.create(uri)).GET().build())

    /** Parse the `402` offers, choose a payable one, and sign it — or explain why nothing was paid. */
    private fun payFor(body: String): SignedPayment {
        val offers = parseAccepts(body)
        if (offers.isEmpty()) throw X402PaymentDeniedException("402 body carried no usable accepts[] offer")

        val reasons = mutableListOf<String>()
        for (offer in offers) {
            val reason = account.reasonCannotPay(offer)
            if (reason == null) return account.authorize(offer, x402Version)
            reasons += "[${offer.network} ${offer.maxAmountRequired} -> ${offer.payTo}] $reason"
        }
        val detail = reasons.joinToString("; ")
        throw X402PaymentDeniedException("no acceptable x402 offer; tried ${offers.size}: $detail")
    }

    private fun parseAccepts(body: String): List<PaymentRequirements> {
        val root = LenientJsonParser.parse(body) as? Map<*, *>
            ?: throw X402Exception("402 body is not a JSON object")
        val accepts = root["accepts"] as? List<*> ?: return emptyList()
        return accepts.filterIsInstance<Map<*, *>>().mapNotNull { PaymentRequirements.fromJsonObject(it) }
    }

    private companion object {
        const val CONNECT_TIMEOUT_SECONDS = 10L
    }
}
