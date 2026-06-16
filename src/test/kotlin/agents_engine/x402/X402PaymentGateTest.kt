package agents_engine.x402

import agents_engine.generation.LenientJsonParser
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// #4527 (PRD §12.8) — seller-side x402. Hermetic: a fake FacilitatorClient (no chain, no money) + an
// in-process JDK HttpServer. Proves the gate returns 402 with terms when unpaid, serves + settles when paid,
// and fails closed when the payment is invalid or the facilitator errors.
class X402PaymentGateTest {

    private val http = HttpClient.newHttpClient()

    private class FakeFacilitator(
        private val valid: Boolean = true,
        private val settled: Boolean = true,
        private val verifyThrows: Boolean = false,
    ) : FacilitatorClient {
        var verifyCalls = 0
        var settleCalls = 0

        override fun verify(paymentHeader: String, requirements: PaymentRequirements): FacilitatorVerification {
            verifyCalls++
            if (verifyThrows) throw X402Exception("facilitator down")
            return FacilitatorVerification(valid, invalidReason = if (valid) null else "bad sig", payer = "0xPayer")
        }

        override fun settle(paymentHeader: String, requirements: PaymentRequirements): FacilitatorSettlement {
            settleCalls++
            return FacilitatorSettlement(
                success = settled, transaction = "0xTX", network = requirements.network,
                payer = "0xPayer", errorReason = if (settled) null else "insufficient funds",
            )
        }
    }

    private val requirements = PaymentRequirements(
        network = "base-sepolia",
        maxAmountRequired = "1000",
        payTo = "0xSeller",
        asset = "0xUSDC",
        resource = "/premium",
    )

    private fun serve(facilitator: FacilitatorClient, settle: Boolean = true): Pair<String, () -> Unit> {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val downstream = HttpHandler { ex ->
            val bytes = "premium content".toByteArray()
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.createContext("/premium", X402PaymentGate(requirements, facilitator, settle = settle).gate(downstream))
        server.start()
        val url = "http://127.0.0.1:${server.address.port}/premium"
        return url to { server.stop(0) }
    }

    private fun get(url: String, payment: String? = null): HttpResponse<String> {
        val b = HttpRequest.newBuilder().uri(URI.create(url)).GET()
        payment?.let { b.header("X-PAYMENT", it) }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString())
    }

    @Test
    fun `unpaid request gets 402 with the payment terms`() {
        val (url, stop) = serve(FakeFacilitator())
        try {
            val resp = get(url)
            assertEquals(402, resp.statusCode())
            @Suppress("UNCHECKED_CAST")
            val body = LenientJsonParser.parse(resp.body()) as Map<String, Any?>
            @Suppress("UNCHECKED_CAST")
            val accepts = body["accepts"] as List<Map<String, Any?>>
            assertEquals("0xSeller", accepts.single()["payTo"])
            assertEquals("1000", accepts.single()["maxAmountRequired"])
            assertFalse(resp.body().contains("premium content"), "must not serve the resource unpaid")
        } finally {
            stop()
        }
    }

    @Test
    fun `valid payment is settled and the resource is served with X-PAYMENT-RESPONSE`() {
        val fac = FakeFacilitator(valid = true, settled = true)
        val (url, stop) = serve(fac)
        try {
            val resp = get(url, payment = "dummy-base64-payment")
            assertEquals(200, resp.statusCode())
            assertEquals("premium content", resp.body())
            assertEquals(1, fac.settleCalls)
            val settleHeader = resp.headers().firstValue("X-PAYMENT-RESPONSE").orElse("")
            assertTrue(settleHeader.isNotBlank(), "X-PAYMENT-RESPONSE must be set")
            val decoded = String(Base64.getDecoder().decode(settleHeader))
            assertTrue("0xTX" in decoded, decoded)
        } finally {
            stop()
        }
    }

    @Test
    fun `invalid payment fails closed without settling or serving`() {
        val fac = FakeFacilitator(valid = false)
        val (url, stop) = serve(fac)
        try {
            val resp = get(url, payment = "dummy")
            assertEquals(402, resp.statusCode())
            assertEquals(0, fac.settleCalls, "must not settle an invalid payment")
            assertFalse(resp.body().contains("premium content"))
        } finally {
            stop()
        }
    }

    @Test
    fun `facilitator failure fails closed`() {
        val (url, stop) = serve(FakeFacilitator(verifyThrows = true))
        try {
            assertEquals(402, get(url, payment = "dummy").statusCode())
        } finally {
            stop()
        }
    }

    @Test
    fun `verify-only gate serves without settling when settle is disabled`() {
        val fac = FakeFacilitator(valid = true)
        val (url, stop) = serve(fac, settle = false)
        try {
            val resp = get(url, payment = "dummy")
            assertEquals(200, resp.statusCode())
            assertEquals("premium content", resp.body())
            assertEquals(0, fac.settleCalls)
        } finally {
            stop()
        }
    }
}
