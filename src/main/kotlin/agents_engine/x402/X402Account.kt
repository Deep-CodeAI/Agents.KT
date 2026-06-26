package agents_engine.x402

import agents_engine.mcp.McpJson
import agents_engine.x402.crypto.Eip712
import agents_engine.x402.crypto.Eip712Domain
import java.math.BigInteger
import java.time.Instant
import java.util.Base64

/**
 * `agents_engine/x402/X402Account.kt` — #4528 (PRD §12.8). The **buyer's payment authorizer**. Given a
 * seller's [PaymentRequirements], it builds an [EIP-3009](https://eips.ethereum.org/EIPS/eip-3009)
 * `TransferWithAuthorization`, builds the EIP-712 digest ([Eip712]), delegates the signature to an
 * [X402Signer], and packs the x402 `X-PAYMENT` header — but only after [policy] permits the payment.
 *
 * **Below the model layer (the whole point of #4528).** The signing key lives in the [X402Signer] (a local
 * key, or a KMS/HSM/session-key), never serialized, logged, or placed in a prompt; the LLM drives the
 * *request* but can neither reach the key nor widen [policy]. Construct one per wallet and inject it into an
 * [X402Client].
 *
 * The token's EIP-712 domain (`name`/`version`) is read from `requirements.extra` (sellers advertise it, e.g.
 * USDC = `"USD Coin"`/`"2"`); the [chainId] for the network comes from the built-in map plus any
 * [extraChainIds] overrides. An offer missing either, or in an unsupported scheme, is one the account declines
 * to sign (see [reasonCannotPay]).
 */
class X402Account private constructor(
    private val signer: X402Signer,
    val policy: X402SpendPolicy,
    private val chainIds: Map<String, Long>,
    private val clockSeconds: () -> Long,
) {
    /** The payer's address (from the [X402Signer]). */
    val address: String get() = signer.address

    /**
     * Why this account will not pay [requirements], or null if it will. Checks scheme support, the token EIP-712
     * domain, the network → chainId mapping, then the [policy] guardrails. An [X402Client] uses this to pick a
     * payable offer out of a `402`'s `accepts[]`; [authorize] enforces it.
     */
    fun reasonCannotPay(requirements: PaymentRequirements): String? {
        if (requirements.scheme != SCHEME_EXACT) {
            return "unsupported scheme '${requirements.scheme}' (only '$SCHEME_EXACT' is signed)"
        }
        if (domainName(requirements) == null || domainVersion(requirements) == null) {
            return "offer is missing the token EIP-712 domain (extra.name / extra.version)"
        }
        if (chainIdFor(requirements.network) == null) {
            return "no chainId is known for network '${requirements.network}' (pass extraChainIds)"
        }
        val value = parseValue(requirements.maxAmountRequired) ?: return "maxAmountRequired is not an integer"
        return policy.reject(planFor(requirements, value))
    }

    /**
     * Build, sign, and base64-encode the `X-PAYMENT` header authorizing payment of [requirements]. Throws
     * [X402PaymentDeniedException] if [reasonCannotPay] is non-null — the account never signs an offer its
     * policy rejects.
     */
    internal fun authorize(requirements: PaymentRequirements, x402Version: Int): SignedPayment {
        reasonCannotPay(requirements)?.let { throw X402PaymentDeniedException("refusing to pay: $it") }

        val now = clockSeconds()
        // Clamp the authorization lifetime to the policy cap — a seller's maxTimeoutSeconds cannot mint a
        // longer-lived authorization against the buyer's key than the policy allows (shorter is settle-safe).
        val lifetime = policy.maxAuthorizationLifetimeSeconds
            ?.let { minOf(requirements.maxTimeoutSeconds.toLong(), it) }
            ?: requirements.maxTimeoutSeconds.toLong()
        val authorization = PaymentAuthorization(
            from = address,
            to = requirements.payTo,
            value = parseValue(requirements.maxAmountRequired)!!,
            validAfter = BigInteger.ZERO,
            validBefore = BigInteger.valueOf(now + lifetime),
            nonce = PaymentAuthorization.randomNonce(),
        )
        val domain = Eip712Domain(
            name = domainName(requirements)!!,
            version = domainVersion(requirements)!!,
            chainId = chainIdFor(requirements.network)!!,
            verifyingContract = requirements.asset,
        )
        val signature = signer.sign(Eip712.digest(domain, authorization))
        val header = encodeHeader(requirements, authorization, signature, x402Version)
        return SignedPayment(authorization, signature, header)
    }

    private fun encodeHeader(
        requirements: PaymentRequirements,
        authorization: PaymentAuthorization,
        signature: String,
        x402Version: Int,
    ): String {
        val payload = linkedMapOf<String, Any?>(
            "x402Version" to x402Version,
            "scheme" to requirements.scheme,
            "network" to requirements.network,
            "payload" to linkedMapOf(
                "signature" to signature,
                "authorization" to authorization.toJsonObject(),
            ),
        )
        return Base64.getEncoder().encodeToString(McpJson.encode(payload).toByteArray(Charsets.UTF_8))
    }

    private fun domainName(r: PaymentRequirements): String? = r.extra["name"] as? String
    private fun domainVersion(r: PaymentRequirements): String? = r.extra["version"] as? String
    private fun chainIdFor(network: String): Long? = chainIds[network.lowercase()]
    private fun parseValue(raw: String): BigInteger? = raw.toBigIntegerOrNull()?.takeIf { it.signum() >= 0 }

    private fun planFor(r: PaymentRequirements, value: BigInteger): PaymentPlan =
        PaymentPlan(network = r.network, payTo = r.payTo, value = value, asset = r.asset, resource = r.resource)

    companion object {
        private const val SCHEME_EXACT = "exact"

        /** x402's common EVM networks → chainId. Buyers on other chains pass `extraChainIds`. */
        private val DEFAULT_CHAIN_IDS: Map<String, Long> = mapOf(
            "ethereum" to 1L,
            "mainnet" to 1L,
            "sepolia" to 11_155_111L,
            "base" to 8453L,
            "base-sepolia" to 84_532L,
            "polygon" to 137L,
            "polygon-amoy" to 80_002L,
            "avalanche" to 43_114L,
            "avalanche-fuji" to 43_113L,
            "arbitrum" to 42_161L,
            "optimism" to 10L,
        )

        /**
         * An account from a raw secp256k1 private key (`0x…`, 32 bytes). The buyer address is derived from it.
         * [policy] is **required** — guardrails-first is the whole point of the buyer side. For an intentionally
         * unbounded wallet (tests, or a deliberate choice) pass `X402SpendPolicy.unsafeAllowAllForTesting()`.
         * [extraChainIds] augments/overrides the built-in network → chainId map.
         */
        fun fromPrivateKey(
            privateKeyHex: String,
            policy: X402SpendPolicy,
            extraChainIds: Map<String, Long> = emptyMap(),
            clockSeconds: () -> Long = { Instant.now().epochSecond },
        ): X402Account = fromSigner(LocalKeySigner(privateKeyHex), policy, extraChainIds, clockSeconds)

        /**
         * An account that signs through an arbitrary [X402Signer] (KMS / HSM / wallet-service / scoped session
         * key) — so a permanent private key need never live in the application heap. [policy] is required (see
         * [fromPrivateKey]).
         */
        fun fromSigner(
            signer: X402Signer,
            policy: X402SpendPolicy,
            extraChainIds: Map<String, Long> = emptyMap(),
            clockSeconds: () -> Long = { Instant.now().epochSecond },
        ): X402Account {
            val chains = DEFAULT_CHAIN_IDS + extraChainIds.mapKeys { it.key.lowercase() }
            return X402Account(signer, policy, chains, clockSeconds)
        }
    }
}
