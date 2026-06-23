package agents_engine.ap2

import agents_engine.agntcy.identity.BadgeVerificationException
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import java.math.BigInteger
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

// AP2 (PRD §12.10) spike — mandate verification reuses the shipped IdentityVerifier (a mandate IS a JWS VC),
// so crypto forgery-resistance comes for free; this proves the typed mandate model + the authorization chain
// (a Cart Mandate is payable only if its Intent Mandate actually authorized this spend).
class MandateVerifierTest {

    private val issuerKey: ECKey = ECKeyGenerator(Curve.P_256).keyID("ap2-issuer").generate()
    private val jwks = JWKSet(issuerKey.toPublicJWK())
    private val seller = "0x209693Bc6afc0C5328bA36FaF03C514EF312287C"
    private val usdc = "0x036CbD53842c5426634e7929541eC2318f3dCF7e"

    private fun mint(
        subject: String,
        credentialSubject: Map<String, Any?>,
        expiresInMillis: Long = 3_600_000,
    ): String {
        val now = System.currentTimeMillis()
        val claims = JWTClaimsSet.Builder()
            .issuer("https://wallet.example").subject(subject)
            .issueTime(Date(now)).expirationTime(Date(now + expiresInMillis))
            .claim("vc", mapOf("credentialSubject" to credentialSubject))
            .build()
        val jwt = SignedJWT(JWSHeader.Builder(JWSAlgorithm.ES256).keyID("ap2-issuer").build(), claims)
        jwt.sign(ECDSASigner(issuerKey))
        return jwt.serialize()
    }

    private fun intentJws(maxAmount: String = "5000", merchants: List<String> = listOf(seller)) = mint(
        "agent:shopper",
        mapOf("mandateId" to "intent-1", "maxAmount" to maxAmount, "asset" to usdc,
            "network" to "base-sepolia", "allowedMerchants" to merchants),
    )

    private fun cartJws(amount: String = "1000", payTo: String = seller, intentId: String = "intent-1") = mint(
        "agent:shopper",
        mapOf("cartId" to "cart-1", "intentId" to intentId, "amount" to amount, "payTo" to payTo,
            "asset" to usdc, "network" to "base-sepolia", "resource" to "https://seller.example/checkout"),
    )

    @Test
    fun `a genuine intent mandate verifies into the typed model`() {
        val intent = MandateVerifier.verifyIntent(intentJws(), jwks)
        assertEquals("intent-1", intent.mandateId)
        assertEquals(BigInteger.valueOf(5000), intent.maxAmount)
        assertEquals("base-sepolia", intent.network)
        assertEquals(listOf(seller), intent.allowedMerchants)
        assertTrue(intent.expiresAt != null)
    }

    @Test
    fun `a genuine cart mandate verifies into the typed model`() {
        val cart = MandateVerifier.verifyCart(cartJws(), jwks)
        assertEquals("cart-1", cart.cartId)
        assertEquals("intent-1", cart.intentId)
        assertEquals(BigInteger.valueOf(1000), cart.amount)
        assertEquals(seller, cart.payTo)
    }

    @Test
    fun `a cart within its intent is authorized`() {
        val intent = MandateVerifier.verifyIntent(intentJws(), jwks)
        val cart = MandateVerifier.verifyCart(cartJws(), jwks)
        assertNull(MandateVerifier.reasonNotAuthorized(cart, intent))
    }

    @Test
    fun `a cart over the intent cap is refused`() {
        val intent = MandateVerifier.verifyIntent(intentJws(maxAmount = "500"), jwks)
        val cart = MandateVerifier.verifyCart(cartJws(amount = "1000"), jwks)
        assertTrue("exceeds the intent cap" in MandateVerifier.reasonNotAuthorized(cart, intent)!!)
    }

    @Test
    fun `a cart to a non-allowed merchant is refused`() {
        val intent = MandateVerifier.verifyIntent(intentJws(merchants = listOf("0xAAaa000000000000000000000000000000000000")), jwks)
        val cart = MandateVerifier.verifyCart(cartJws(payTo = seller), jwks)
        assertTrue("not in the intent's allowed merchants" in MandateVerifier.reasonNotAuthorized(cart, intent)!!)
    }

    @Test
    fun `a cart referencing a different intent is refused`() {
        val intent = MandateVerifier.verifyIntent(intentJws(), jwks)
        val cart = MandateVerifier.verifyCart(cartJws(intentId = "intent-OTHER"), jwks)
        assertTrue("does not match the intent" in MandateVerifier.reasonNotAuthorized(cart, intent)!!)
    }

    @Test
    fun `an expired mandate is rejected by the identity verifier (crypto layer, for free)`() {
        val expired = mint("agent:shopper",
            mapOf("mandateId" to "i", "maxAmount" to "1", "asset" to usdc, "network" to "base-sepolia",
                "allowedMerchants" to listOf(seller)),
            expiresInMillis = -60_000)
        assertFailsWith<BadgeVerificationException> { MandateVerifier.verifyIntent(expired, jwks) }
    }

    @Test
    fun `a tampered mandate is rejected by the identity verifier`() {
        val parts = intentJws().split(".")
        val forgedPayload = com.nimbusds.jose.util.Base64URL.encode("""{"sub":"agent:evil"}""").toString()
        assertFailsWith<BadgeVerificationException> {
            MandateVerifier.verifyIntent("${parts[0]}.$forgedPayload.${parts[2]}", jwks)
        }
    }

    @Test
    fun `a malformed mandate (missing field) is rejected as an Ap2MandateException`() {
        val missing = mint("agent:shopper", mapOf("mandateId" to "i")) // no maxAmount/asset/...
        assertFailsWith<Ap2MandateException> { MandateVerifier.verifyIntent(missing, jwks) }
    }
}
