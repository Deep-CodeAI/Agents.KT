package agents_engine.x402

import agents_engine.x402.crypto.Hex
import agents_engine.x402.crypto.Secp256k1
import java.math.BigInteger

/**
 * `agents_engine/x402/LocalKeySigner.kt` — #4528 (PRD §12.8). The default [X402Signer]: an in-process secp256k1
 * private key. Derives the payer [address] from the key and signs EIP-712 digests with it (RFC-6979 + low-s +
 * recovery byte — see [Secp256k1]).
 *
 * Simplest and most common, but the key lives in heap — for production, prefer a KMS/HSM/session-key signer
 * behind the [X402Signer] seam. Construct via [X402Account.fromPrivateKey] (which wraps a key in this) or
 * directly for [X402Account.fromSigner].
 */
class LocalKeySigner(privateKeyHex: String) : X402Signer {
    private val privateKey: BigInteger = BigInteger(1, Hex.decode(privateKeyHex)).also {
        require(it.signum() > 0) { "private key must be non-zero" }
    }

    override val address: String = Secp256k1.deriveAddress(privateKey)

    override fun sign(digest: ByteArray): String = Secp256k1.sign(digest, privateKey).toHex()
}
