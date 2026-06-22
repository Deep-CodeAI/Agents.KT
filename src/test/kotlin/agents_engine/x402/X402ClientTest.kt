package agents_engine.x402

import agents_engine.generation.LenientJsonParser
import agents_engine.x402.crypto.Eip712
import agents_engine.x402.crypto.Eip712Domain
import agents_engine.x402.crypto.Secp256k1
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpRequest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// #4528 (PRD §12.8) — buyer side end-to-end. The headline test stands up the REAL seller gate (X402PaymentGate)
// fronted by a facilitator that independently rebuilds the EIP-712 digest and ecrecovers the signer, and the
// REAL buyer (X402Client) — so a genuine EIP-3009 signature flows buyer -> 402 -> sign -> seller -> verify ->
// 200, hermetically (no chain, no money, real cryptography).
class X402ClientTest {

    private val pk = "0x0000000000000000000000000000000000000000000000000000000000000001"
    private val payerAddress = "0x7e5f4552091a69125d5dfcb7b8c2659029395bdf"
    private val seller = "0x209693Bc6afc0C5328bA36FaF03C514EF312287C"
    private val usdc = "0x036CbD53842c5426634e7929541eC2318f3dCF7e"

    private val requirements = PaymentRequirements(
        network = "base-sepolia",
        maxAmountRequired = "1000",
        payTo = seller,
        asset = usdc,
        resource = "/premium",
        extra = mapOf("name" to "USD Coin", "version" to "2"),
    )

    // A facilitator that does the real cryptographic check the hosted one would: recover the signer from the
    // EIP-712 signature and confirm it authorized THIS transfer. No chain — settle is a stub success.
    private class RecoveringFacilitator(private val expectedPayer: String) : FacilitatorClient {
        var lastPayer: String? = null

        override fun verify(paymentHeader: String, requirements: PaymentRequirements): FacilitatorVerification {
            val payload = decode(paymentHeader)
            @Suppress("UNCHECKED_CAST")
            val inner = payload["payload"] as Map<String, Any?>
            @Suppress("UNCHECKED_CAST")
            val a = inner["authorization"] as Map<String, Any?>
            val auth = PaymentAuthorization(
                from = a["from"] as String,
                to = a["to"] as String,
                value = (a["value"] as String).toBigInteger(),
                validAfter = (a["validAfter"] as String).toBigInteger(),
                validBefore = (a["validBefore"] as String).toBigInteger(),
                nonce = a["nonce"] as String,
            )
            val domain = Eip712Domain(
                name = requirements.extra["name"] as String,
                version = requirements.extra["version"] as String,
                chainId = 84_532L,
                verifyingContract = requirements.asset,
            )
            val sig = inner["signature"] as String
            val signature = agents_engine.x402.crypto.EcdsaSignature(
                r = BigInteger(sig.substring(2, 66), 16),
                s = BigInteger(sig.substring(66, 130), 16),
                v = sig.substring(130, 132).toInt(16),
            )
            val recovered = Secp256k1.recoverAddress(Eip712.digest(domain, auth), signature)
            lastPayer = recovered
            val ok = recovered.equals(expectedPayer, ignoreCase = true) &&
                auth.to.equals(requirements.payTo, ignoreCase = true) &&
                auth.value >= requirements.maxAmountRequired.toBigInteger()
            val reason = if (ok) null else "signature/terms mismatch"
            return FacilitatorVerification(ok, invalidReason = reason, payer = recovered)
        }

        override fun settle(paymentHeader: String, requirements: PaymentRequirements) = FacilitatorSettlement(
            success = true, transaction = "0xTX", network = requirements.network, payer = lastPayer,
        )

        private fun decode(header: String): Map<*, *> =
            LenientJsonParser.parse(String(Base64.getDecoder().decode(header), Charsets.UTF_8)) as Map<*, *>
    }

    private fun serve(facilitator: FacilitatorClient): Pair<String, () -> Unit> {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val downstream = HttpHandler { ex ->
            val bytes = "premium content".toByteArray()
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.createContext("/premium", X402PaymentGate(requirements, facilitator).gate(downstream))
        server.start()
        return "http://127.0.0.1:${server.address.port}/premium" to { server.stop(0) }
    }

    private fun client(policy: X402SpendPolicy = X402SpendPolicy(maxValuePerPayment = BigInteger.valueOf(10_000))) =
        X402Client(X402Account.fromPrivateKey(pk, policy))

    @Test
    fun `buyer pays a 402 challenge and the recovered signature lets the seller serve the resource`() {
        val fac = RecoveringFacilitator(expectedPayer = payerAddress)
        val (url, stop) = serve(fac)
        try {
            val resp = client().get(url)
            assertEquals(200, resp.statusCode())
            assertEquals("premium content", resp.body())
            assertEquals(payerAddress, fac.lastPayer?.lowercase(), "facilitator recovered the buyer's address")
            assertTrue(resp.headers().firstValue("X-PAYMENT-RESPONSE").isPresent, "settlement receipt returned")
        } finally {
            stop()
        }
    }

    @Test
    fun `a non-402 response is returned without paying`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/free", HttpHandler { ex ->
            val bytes = "free".toByteArray()
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        })
        server.start()
        try {
            val resp = client().get("http://127.0.0.1:${server.address.port}/free")
            assertEquals(200, resp.statusCode())
            assertEquals("free", resp.body())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `the buyer refuses when the price exceeds its spend cap`() {
        val (url, stop) = serve(RecoveringFacilitator(payerAddress))
        try {
            val tightClient = client(X402SpendPolicy(maxValuePerPayment = BigInteger.valueOf(500)))
            val ex = assertFailsWith<X402PaymentDeniedException> { tightClient.get(url) }
            assertTrue("no acceptable x402 offer" in ex.message!!, ex.message!!)
        } finally {
            stop()
        }
    }

    @Test
    fun `send preserves the original POST body when paying`() {
        val fac = RecoveringFacilitator(payerAddress)
        val (url, stop) = serve(fac)
        try {
            val req = HttpRequest.newBuilder().uri(URI.create(url))
                .POST(HttpRequest.BodyPublishers.ofString("hello")).build()
            val resp = client().send(req)
            assertEquals(200, resp.statusCode())
        } finally {
            stop()
        }
    }

    @Test
    fun `payerAddress exposes the buyer wallet`() {
        assertEquals(payerAddress, client().payerAddress)
    }
}
