package agents_engine.ap2

import agents_engine.x402.FacilitatorClient
import agents_engine.x402.FacilitatorSettlement
import agents_engine.x402.FacilitatorVerification
import agents_engine.x402.PaymentRequirements
import agents_engine.x402.X402PaymentGate
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// AP2 (PRD §12.10) spike — the headline: prove the 1+1=3 assembly end-to-end, hermetically. Mint Intent + Cart
// mandates (signed VC-JWTs) -> verify via the shipped IdentityVerifier -> derive an x402 spend policy FROM the
// intent -> settle the cart against a REAL X402PaymentGate. A genuine mandate chain flows
// user-intent -> cart -> x402 settlement, with no chain and no real money (the facilitator is faked; x402's own
// signature crypto is already proven in the root X402CryptoTest).
class Ap2EndToEndTest {

    private val issuerKey: ECKey = ECKeyGenerator(Curve.P_256).keyID("ap2-issuer").generate()
    private val jwks = JWKSet(issuerKey.toPublicJWK())
    private val buyerKey = "0x0000000000000000000000000000000000000000000000000000000000000001"
    private val seller = "0x209693Bc6afc0C5328bA36FaF03C514EF312287C"
    private val usdc = "0x036CbD53842c5426634e7929541eC2318f3dCF7e"

    private fun mint(cs: Map<String, Any?>): String {
        val now = System.currentTimeMillis()
        val claims = JWTClaimsSet.Builder().issuer("https://wallet.example").subject("agent:shopper")
            .issueTime(Date(now)).expirationTime(Date(now + 3_600_000))
            .claim("vc", mapOf("credentialSubject" to cs)).build()
        return SignedJWT(JWSHeader.Builder(JWSAlgorithm.ES256).keyID("ap2-issuer").build(), claims)
            .apply { sign(ECDSASigner(issuerKey)) }.serialize()
    }

    // Faked facilitator (no chain, no money) — returns valid+settled. x402's real signing is proven elsewhere.
    private class FakeFacilitator : FacilitatorClient {
        override fun verify(paymentHeader: String, requirements: PaymentRequirements) =
            FacilitatorVerification(isValid = true, invalidReason = null, payer = "0xPayer")
        override fun settle(paymentHeader: String, requirements: PaymentRequirements) =
            FacilitatorSettlement(success = true, transaction = "0xTX", network = requirements.network, payer = "0xPayer")
    }

    private fun serveSeller(price: String): Pair<String, () -> Unit> {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requirements = PaymentRequirements(
            network = "base-sepolia", maxAmountRequired = price, payTo = seller, asset = usdc,
            resource = "/checkout", extra = mapOf("name" to "USD Coin", "version" to "2"),
        )
        val downstream = HttpHandler { ex ->
            val bytes = "order confirmed".toByteArray()
            ex.sendResponseHeaders(200, bytes.size.toLong()); ex.responseBody.use { it.write(bytes) }
        }
        server.createContext("/checkout", X402PaymentGate(requirements, FakeFacilitator()).gate(downstream))
        server.start()
        return "http://127.0.0.1:${server.address.port}/checkout" to { server.stop(0) }
    }

    private fun intent(maxAmount: String = "5000") = MandateVerifier.verifyIntent(
        mint(mapOf("mandateId" to "intent-1", "maxAmount" to maxAmount, "asset" to usdc,
            "network" to "base-sepolia", "allowedMerchants" to listOf(seller))),
        jwks,
    )

    private fun cart(resource: String, amount: String = "1000") = MandateVerifier.verifyCart(
        mint(mapOf("cartId" to "cart-1", "intentId" to "intent-1", "amount" to amount, "payTo" to seller,
            "asset" to usdc, "network" to "base-sepolia", "resource" to resource)),
        jwks,
    )

    @Test
    fun `verified mandate chain settles over x402 end to end`() {
        val (url, stop) = serveSeller(price = "1000")
        try {
            val receipt = Ap2Buyer(buyerKey).pay(intent(), cart(url))
            assertEquals(200, receipt.status)
            assertTrue(receipt.settled)
            assertEquals("order confirmed", receipt.body)
            assertEquals("0x7e5f4552091a69125d5dfcb7b8c2659029395bdf", receipt.payer) // derived from buyerKey
            assertTrue(receipt.paymentResponse != null, "x402 settlement receipt returned")
        } finally {
            stop()
        }
    }

    @Test
    fun `a cart over the intent cap is denied before any payment (AP2 layer)`() {
        val (url, stop) = serveSeller(price = "9000")
        try {
            val ex = assertFailsWith<Ap2PaymentDeniedException> {
                Ap2Buyer(buyerKey).pay(intent(maxAmount = "5000"), cart(url, amount = "9000"))
            }
            assertTrue("exceeds the intent cap" in ex.message!!, ex.message!!)
        } finally {
            stop()
        }
    }

    @Test
    fun `the intent's bound also becomes the x402 spend policy (defense in depth)`() {
        // The same intent that authorizes the cart caps the signing key: maxValuePerPayment == intent.maxAmount.
        val policy = Ap2Buyer(buyerKey).spendPolicyFrom(intent(maxAmount = "2500"))
        assertEquals(java.math.BigInteger.valueOf(2500), policy.maxValuePerPayment)
        assertEquals(setOf(seller), policy.allowedPayTo)
        assertEquals(setOf("base-sepolia"), policy.allowedNetworks)
    }
}
