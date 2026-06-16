package agents_engine.x402

import agents_engine.generation.LenientJsonParser
import agents_engine.mcp.McpJson
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64

/**
 * `agents_engine/x402/HttpFacilitatorClient.kt` — #4527 (PRD §12.8). A [FacilitatorClient] backed by a
 * **hosted** x402 facilitator's REST API (`POST <baseUrl>/verify`, `POST <baseUrl>/settle`). The buyer's
 * `X-PAYMENT` header (base64 JSON) is decoded and forwarded as `paymentPayload` alongside the seller's
 * `paymentRequirements`; the facilitator performs the EIP-712/EIP-3009 checks and the on-chain settle.
 *
 * Bounded timeouts; failures surface as [X402Exception] so [X402PaymentGate] can fail closed (deny access).
 * Using a hosted facilitator is deliberate — the seller never takes custody (see [FacilitatorClient]).
 */
class HttpFacilitatorClient(
    private val baseUrl: String,
    private val bearerToken: String? = null,
    private val x402Version: Int = 1,
    connectTimeout: Duration = Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS),
    private val requestTimeout: Duration = Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS),
) : FacilitatorClient {

    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(connectTimeout).build()

    override fun verify(paymentHeader: String, requirements: PaymentRequirements): FacilitatorVerification {
        val json = post("/verify", paymentHeader, requirements)
        return FacilitatorVerification(
            isValid = json["isValid"] as? Boolean ?: false,
            invalidReason = json["invalidReason"] as? String,
            payer = json["payer"] as? String,
        )
    }

    override fun settle(paymentHeader: String, requirements: PaymentRequirements): FacilitatorSettlement {
        val json = post("/settle", paymentHeader, requirements)
        return FacilitatorSettlement(
            success = json["success"] as? Boolean ?: false,
            transaction = json["transaction"] as? String,
            network = json["network"] as? String,
            payer = json["payer"] as? String,
            errorReason = json["errorReason"] as? String,
        )
    }

    private fun post(path: String, paymentHeader: String, requirements: PaymentRequirements): Map<*, *> {
        val body = McpJson.encode(
            linkedMapOf(
                "x402Version" to x402Version,
                "paymentPayload" to decodePaymentPayload(paymentHeader),
                "paymentRequirements" to requirements.toJsonObject(),
            ),
        )
        val responseBody = send(path, body)
        return LenientJsonParser.parse(responseBody) as? Map<*, *>
            ?: throw X402Exception("facilitator $path returned a non-object body")
    }

    private fun send(path: String, body: String): String {
        val builder = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl.trimEnd('/') + path))
            .timeout(requestTimeout)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
        bearerToken?.let { builder.header("Authorization", "Bearer $it") }
        val resp = try {
            http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) {
            throw X402Exception("facilitator $path request failed: ${e.message}", e)
        }
        if (resp.statusCode() !in HTTP_OK_RANGE) {
            val detail = resp.body().take(MAX_ERROR_BODY)
            throw X402Exception("facilitator $path returned HTTP ${resp.statusCode()}: $detail")
        }
        return resp.body()
    }

    private fun decodePaymentPayload(paymentHeader: String): Any? {
        val decoded = try {
            String(Base64.getDecoder().decode(paymentHeader.trim()), Charsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            throw X402Exception("X-PAYMENT header is not valid base64", e)
        }
        return LenientJsonParser.parse(decoded) ?: throw X402Exception("X-PAYMENT payload is not valid JSON")
    }

    private companion object {
        const val CONNECT_TIMEOUT_SECONDS = 10L
        const val REQUEST_TIMEOUT_SECONDS = 30L
        const val MAX_ERROR_BODY = 300
        val HTTP_OK_RANGE = 200..299
    }
}
