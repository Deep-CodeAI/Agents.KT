package agents_engine.x402

import agents_engine.mcp.McpJson
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import java.util.Base64

/**
 * `agents_engine/x402/X402PaymentGate.kt` — #4527 (PRD §12.8). **Seller-side**
 * [x402](https://github.com/x402-foundation/x402)
 * payment gating: wrap any JDK [HttpHandler] so the protected resource is served only after a valid,
 * settled stablecoin payment. The agentic-web serve surfaces (`McpServer` / `A2AServer` / `NlWebServer` /
 * `AgUiServer`) are loopback JDK `HttpServer`s, so this gate fronts any of them — letting an agent **monetize
 * itself** (the safe half of x402: receive USDC via a hosted facilitator, no custody, no key held here).
 *
 * Per request:
 * - **no `X-PAYMENT` header** → `402 Payment Required` with body `{x402Version, error, accepts:[requirements]}`
 *   so the buyer knows the terms.
 * - **`X-PAYMENT` present** → [FacilitatorClient.verify]; if valid and [settle], [FacilitatorClient.settle];
 *   on success, set `X-PAYMENT-RESPONSE` and invoke the downstream handler. Any failure (invalid, settle
 *   error, facilitator unreachable) → `402`. **Fails closed** — the resource is never served unpaid.
 *
 * Settle-before-serve: the `X-PAYMENT-RESPONSE` header must be set before the downstream writes the response,
 * so settlement happens first. This charges before delivering; acceptable for the seller path (the buyer
 * already authorized the amount).
 *
 * EXPERIMENTAL (seller-side only). Buyer-side autonomous payment is deliberately NOT here — it concentrates
 * the irreversible-money risk and is gated on scoped session keys + signing kept below the model layer.
 */
class X402PaymentGate(
    private val requirements: PaymentRequirements,
    private val facilitator: FacilitatorClient,
    private val settle: Boolean = true,
    private val x402Version: Int = 1,
) {
    /** Wrap [downstream] so it is reached only after payment; returns the gating handler. */
    fun gate(downstream: HttpHandler): HttpHandler = HttpHandler { exchange ->
        val header = exchange.requestHeaders.getFirst("X-PAYMENT")
        if (header.isNullOrBlank()) {
            respondPaymentRequired(exchange, "X-PAYMENT header required")
            return@HttpHandler
        }

        val verification = try {
            facilitator.verify(header, requirements)
        } catch (e: X402Exception) {
            respondPaymentRequired(exchange, "verification failed: ${e.message}")
            return@HttpHandler
        }
        if (!verification.isValid) {
            respondPaymentRequired(exchange, verification.invalidReason ?: "payment is not valid")
            return@HttpHandler
        }

        if (settle) {
            val settlement = try {
                facilitator.settle(header, requirements)
            } catch (e: X402Exception) {
                respondPaymentRequired(exchange, "settlement failed: ${e.message}")
                return@HttpHandler
            }
            if (!settlement.success) {
                respondPaymentRequired(exchange, settlement.errorReason ?: "settlement was not successful")
                return@HttpHandler
            }
            exchange.responseHeaders.add("X-PAYMENT-RESPONSE", encodeSettlement(settlement))
        }

        downstream.handle(exchange)
    }

    private fun respondPaymentRequired(exchange: HttpExchange, error: String) {
        val body = McpJson.encode(
            linkedMapOf(
                "x402Version" to x402Version,
                "error" to error,
                "accepts" to listOf(requirements.toJsonObject()),
            ),
        )
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(HTTP_PAYMENT_REQUIRED, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
        exchange.close()
    }

    private fun encodeSettlement(s: FacilitatorSettlement): String {
        val obj = McpJson.encode(
            linkedMapOf(
                "success" to s.success,
                "transaction" to s.transaction,
                "network" to s.network,
                "payer" to s.payer,
            ),
        )
        return Base64.getEncoder().encodeToString(obj.toByteArray(Charsets.UTF_8))
    }

    companion object {
        const val HTTP_PAYMENT_REQUIRED: Int = 402
    }
}
