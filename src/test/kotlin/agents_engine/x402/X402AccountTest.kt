package agents_engine.x402

import agents_engine.generation.LenientJsonParser
import agents_engine.x402.crypto.Eip712
import agents_engine.x402.crypto.Eip712Domain
import agents_engine.x402.crypto.Secp256k1
import java.math.BigInteger
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// #4528 (PRD §12.8) — buyer-side account + spend-policy guardrails. The guardrails are the safety story for
// irreversible money, so they get the most coverage: a payment the policy rejects must never be signed.
class X402AccountTest {

    private val pk = "0x0000000000000000000000000000000000000000000000000000000000000001"
    private val payerAddress = "0x7e5f4552091a69125d5dfcb7b8c2659029395bdf"

    private val usdc = "0x036CbD53842c5426634e7929541eC2318f3dCF7e"

    private fun requirements(
        value: String = "1000",
        network: String = "base-sepolia",
        payTo: String = "0x209693Bc6afc0C5328bA36FaF03C514EF312287C",
        scheme: String = "exact",
        asset: String = usdc,
        resource: String = "https://seller.example/premium",
        maxTimeoutSeconds: Int = 60,
        extra: Map<String, Any?> = mapOf("name" to "USD Coin", "version" to "2"),
    ) = PaymentRequirements(
        network = network,
        maxAmountRequired = value,
        payTo = payTo,
        asset = asset,
        resource = resource,
        scheme = scheme,
        maxTimeoutSeconds = maxTimeoutSeconds,
        extra = extra,
    )

    private fun account(policy: X402SpendPolicy = X402SpendPolicy.unsafeAllowAllForTesting()) =
        X402Account.fromPrivateKey(pk, policy, clockSeconds = { 1_750_000_000L })

    @Test
    fun `derives the payer address from the key`() {
        assertEquals(payerAddress, account().address)
    }

    @Test
    fun `a within-limits payment is permitted`() {
        val acct = account(X402SpendPolicy(maxValuePerPayment = BigInteger.valueOf(5000)))
        assertNull(acct.reasonCannotPay(requirements(value = "1000")))
    }

    @Test
    fun `a payment over the per-payment cap is refused`() {
        val acct = account(X402SpendPolicy(maxValuePerPayment = BigInteger.valueOf(500)))
        val reason = acct.reasonCannotPay(requirements(value = "1000"))
        assertNotNull(reason)
        assertTrue("exceeds maxValuePerPayment" in reason, reason)
        // and authorize() must throw rather than sign it
        assertFailsWith<X402PaymentDeniedException> { acct.authorize(requirements(value = "1000"), x402Version = 1) }
    }

    @Test
    fun `a payTo outside the allowlist is refused`() {
        val acct = account(X402SpendPolicy(allowedPayTo = setOf("0xAAAA000000000000000000000000000000000000")))
        assertTrue("not in the allowed recipients" in acct.reasonCannotPay(requirements())!!)
    }

    @Test
    fun `a network outside the allowlist is refused`() {
        val acct = account(X402SpendPolicy(allowedNetworks = setOf("base")))
        assertTrue("not in the allowed set" in acct.reasonCannotPay(requirements(network = "base-sepolia"))!!)
    }

    @Test
    fun `the confirm gate can veto an otherwise-allowed payment`() {
        val acct = account(X402SpendPolicy(confirm = { false }))
        assertTrue("declined by the confirm" in acct.reasonCannotPay(requirements())!!)
    }

    @Test
    fun `confirm sees the real plan and can approve`() {
        var seen: PaymentPlan? = null
        val acct = account(X402SpendPolicy(confirm = { seen = it; true }))
        assertNull(acct.reasonCannotPay(requirements(value = "2500")))
        assertEquals(BigInteger.valueOf(2500), seen?.value)
        assertEquals("base-sepolia", seen?.network)
    }

    @Test
    fun `an offer missing the token EIP-712 domain is refused`() {
        assertTrue("missing the token EIP-712 domain" in account().reasonCannotPay(requirements(extra = emptyMap()))!!)
    }

    @Test
    fun `an unsupported scheme is refused`() {
        assertTrue("unsupported scheme" in account().reasonCannotPay(requirements(scheme = "upto"))!!)
    }

    @Test
    fun `a CAIP-2 EVM network id (eip155 chainId) resolves — x402 v2 interop`() {
        // a v2 seller advertises the network as eip155:84532 rather than "base-sepolia"
        assertNull(account().reasonCannotPay(requirements(network = "eip155:84532")))
        assertNull(account().reasonCannotPay(requirements(network = "eip155:8453")))
        // a non-EVM / unparseable CAIP-2 network is still refused (no EVM chainId)
        assertTrue("no chainId is known" in account().reasonCannotPay(requirements(network = "solana:abc"))!!)
    }

    @Test
    fun `an unknown network is refused`() {
        assertTrue("no chainId is known" in account().reasonCannotPay(requirements(network = "dogechain"))!!)
    }

    @Test
    fun `a payment in a non-allowed asset is refused`() {
        val acct = account(X402SpendPolicy(allowedAssets = setOf(usdc)))
        val reason = acct.reasonCannotPay(requirements(asset = "0xDEADbeefdeadBEEFdeAdBEefDeAdbEEFdeAdbeEf"))
        assertTrue("not in the allowed assets" in reason!!, reason)
    }

    @Test
    fun `an approved asset is permitted`() {
        val acct = account(X402SpendPolicy(allowedAssets = setOf(usdc.uppercase()))) // case-insensitive
        assertNull(acct.reasonCannotPay(requirements(asset = usdc)))
    }

    @Test
    fun `a resource outside the allowed origins is refused`() {
        val acct = account(X402SpendPolicy(allowedResourceOrigins = setOf("https://seller.example")))
        val reason = acct.reasonCannotPay(requirements(resource = "https://evil.example/drain"))
        assertTrue("resource origin" in reason!!, reason)
        // the allowed origin (any path under it) passes
        assertNull(acct.reasonCannotPay(requirements(resource = "https://seller.example/anything")))
    }

    @Test
    fun `the authorization lifetime is clamped to the policy cap`() {
        // seller asks for a 1-hour authorization; policy caps lifetimes at 120s
        val acct = account(X402SpendPolicy(maxAuthorizationLifetimeSeconds = 120))
        val signed = acct.authorize(requirements(maxTimeoutSeconds = 3600), x402Version = 1)
        assertEquals(BigInteger.valueOf(1_750_000_000L + 120), signed.authorization.validBefore)
    }

    @Test
    fun `unsafeAllowAllForTesting permits everything`() {
        val acct = account(X402SpendPolicy.unsafeAllowAllForTesting())
        assertNull(acct.reasonCannotPay(requirements(value = "999999999", payTo = "0xAnyone")))
    }

    @Test
    fun `fromSigner delegates signing to the injected X402Signer`() {
        val captured = mutableListOf<ByteArray>()
        val signer = object : X402Signer {
            override val address = "0x1111111111111111111111111111111111111111" // valid 20-byte hex
            override fun sign(digest: ByteArray): String { captured += digest; return "0x" + "ab".repeat(65) }
        }
        val acct = X402Account.fromSigner(
            signer, X402SpendPolicy.unsafeAllowAllForTesting(), clockSeconds = { 1_750_000_000L },
        )
        assertEquals("0x1111111111111111111111111111111111111111", acct.address)
        val signed = acct.authorize(requirements(), x402Version = 1)
        assertEquals("0x" + "ab".repeat(65), signed.signature) // the signer's signature is used
        assertEquals(1, captured.size)
        assertEquals(32, captured.single().size) // it was handed a 32-byte EIP-712 digest
    }

    @Test
    fun `LocalKeySigner derives the same address as fromPrivateKey`() {
        assertEquals(payerAddress, LocalKeySigner(pk).address)
    }

    @Test
    fun `authorize produces a verifiable X-PAYMENT header`() {
        val signed = account().authorize(requirements(value = "1000"), x402Version = 1)

        // header decodes to the x402 v1 envelope
        @Suppress("UNCHECKED_CAST")
        val payload = LenientJsonParser.parse(
            String(Base64.getDecoder().decode(signed.header), Charsets.UTF_8),
        ) as Map<String, Any?>
        assertEquals(1, (payload["x402Version"] as Number).toInt())
        assertEquals("exact", payload["scheme"])
        assertEquals("base-sepolia", payload["network"])

        @Suppress("UNCHECKED_CAST")
        val inner = payload["payload"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val authJson = inner["authorization"] as Map<String, Any?>
        assertEquals(payerAddress, authJson["from"])
        assertEquals("1000", authJson["value"])
        assertEquals("0", authJson["validAfter"])
        assertEquals("1750000060", authJson["validBefore"]) // clock + maxTimeoutSeconds(60)

        // the signature recovers to the payer over the same EIP-712 digest the seller will rebuild
        val domain = Eip712Domain("USD Coin", "2", 84_532L, "0x036CbD53842c5426634e7929541eC2318f3dCF7e")
        val digest = Eip712.digest(domain, signed.authorization)
        val sig = signed.signature
        val r = BigInteger(sig.substring(2, 66), 16)
        val s = BigInteger(sig.substring(66, 130), 16)
        val v = sig.substring(130, 132).toInt(16)
        assertEquals(payerAddress, Secp256k1.recoverAddress(digest, agents_engine.x402.crypto.EcdsaSignature(r, s, v)))
    }

    @Test
    fun `each authorization uses a fresh nonce`() {
        val a = account().authorize(requirements(), 1).authorization.nonce
        val b = account().authorize(requirements(), 1).authorization.nonce
        assertTrue(a != b, "nonces must be unique for replay protection")
    }
}
