package agents_engine.x402.crypto

import org.bouncycastle.crypto.digests.KeccakDigest

/**
 * `agents_engine/x402/crypto/Keccak256.kt` — #4528 (PRD §12.8). Legacy **Keccak-256** (Ethereum's hash),
 * used to build the EIP-712 digest the x402 buyer signs (see [Eip712]).
 *
 * **Not SHA3-256.** Ethereum predates the finalized SHA-3 standard and uses the original Keccak padding
 * (`0x01`), whereas `MessageDigest.getInstance("SHA3-256")` uses NIST's `0x06` padding — the two produce
 * different digests for the same input. The JDK ships only SHA3, so this routes through BouncyCastle's
 * [KeccakDigest], the one runtime use of bcprov in the codebase.
 */
internal object Keccak256 {
    const val SIZE_BYTES: Int = 32

    /** Keccak-256 of [input]; always 32 bytes. */
    fun hash(input: ByteArray): ByteArray {
        val digest = KeccakDigest(SIZE_BYTES * Byte.SIZE_BITS)
        digest.update(input, 0, input.size)
        val out = ByteArray(SIZE_BYTES)
        digest.doFinal(out, 0)
        return out
    }

    /** Keccak-256 of the concatenation of [parts] (the EIP-712 `keccak256(abi.encode(...))` shape). */
    fun hash(vararg parts: ByteArray): ByteArray = hash(concat(*parts))

    /** Keccak-256 of [text]'s UTF-8 bytes (EIP-712 hashes string fields and type strings this way). */
    fun hashUtf8(text: String): ByteArray = hash(text.toByteArray(Charsets.UTF_8))

    private fun concat(vararg parts: ByteArray): ByteArray {
        val total = parts.sumOf { it.size }
        val out = ByteArray(total)
        var offset = 0
        for (part in parts) {
            part.copyInto(out, offset)
            offset += part.size
        }
        return out
    }
}
