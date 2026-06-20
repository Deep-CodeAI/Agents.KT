package agents_engine.x402

import agents_engine.x402.crypto.Hex
import java.math.BigInteger
import java.security.SecureRandom

/**
 * `agents_engine/x402/PaymentAuthorization.kt` — #4528 (PRD §12.8). The
 * [EIP-3009](https://eips.ethereum.org/EIPS/eip-3009) `transferWithAuthorization` message a buyer signs to
 * authorize a gasless USDC transfer to the seller's `payTo`. The signed form of this struct is the
 * `payload.authorization` of the x402 `X-PAYMENT` header; a facilitator submits it on-chain.
 *
 * @property from payer EOA (the buyer's address — derived from the signing key, never a secret in a prompt).
 * @property to recipient (the seller's `payTo`).
 * @property value amount in the token's atomic units (USDC = 6 decimals).
 * @property validAfter unix-seconds lower bound (0 = immediately valid).
 * @property validBefore unix-seconds expiry — the authorization is unusable after this.
 * @property nonce a unique 32-byte value (`0x…`) that the token contract burns on use (replay protection).
 */
data class PaymentAuthorization(
    val from: String,
    val to: String,
    val value: BigInteger,
    val validAfter: BigInteger,
    val validBefore: BigInteger,
    val nonce: String,
) {
    /** The x402 wire object (decimal strings for amounts, as the protocol/facilitator expects). */
    internal fun toJsonObject(): LinkedHashMap<String, Any?> = linkedMapOf(
        "from" to from,
        "to" to to,
        "value" to value.toString(),
        "validAfter" to validAfter.toString(),
        "validBefore" to validBefore.toString(),
        "nonce" to nonce,
    )

    companion object {
        private const val NONCE_BYTES = 32
        private val RANDOM = SecureRandom()

        /** A fresh random 32-byte nonce (`0x…`) for replay protection. */
        fun randomNonce(): String = Hex.encode(ByteArray(NONCE_BYTES).also { RANDOM.nextBytes(it) })
    }
}
