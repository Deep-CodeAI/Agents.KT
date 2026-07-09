package agents_engine.x402

/**
 * `agents_engine/x402/X402Signer.kt` — #4528 (PRD §12.8). The seam between [X402Account] and the actual signing
 * key. [X402Account] holds the *policy* and builds the EIP-712 digest; it delegates the secp256k1 signature to
 * an `X402Signer`, so the private key need not live as a raw `BigInteger` in the application heap.
 *
 * The default is [LocalKeySigner] (an in-process key, the simplest case). The seam exists so a deployment can
 * sign with a **KMS / HSM / wallet-service / scoped ERC-4337 session key** instead — keeping permanent keys out
 * of ordinary memory, which is exactly where the irreversible-money risk wants them not to be.
 *
 * @property address the payer's Ethereum address (`0x…`, lowercase) — the public half; safe to expose/log.
 */
interface X402Signer {
    val address: String

    /** Sign a 32-byte EIP-712 digest, returning the packed `0x r‖s‖v` (65-byte) signature an EIP-3009 carries. */
    fun sign(digest: ByteArray): String
}
