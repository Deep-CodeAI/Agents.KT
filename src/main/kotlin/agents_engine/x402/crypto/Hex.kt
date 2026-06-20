package agents_engine.x402.crypto

import java.math.BigInteger

/**
 * `agents_engine/x402/crypto/Hex.kt` — #4528 (PRD §12.8). Hex + ABI-word helpers shared by the x402 buyer's
 * EIP-712 encoding ([Eip712]) and signer ([Secp256k1]). EIP-712 `abi.encode` lays every static value out as a
 * 32-byte big-endian word, so addresses and `uint256`s are left-padded to [WORD_BYTES].
 */
internal object Hex {
    const val WORD_BYTES: Int = 32

    /** `0x`-prefixed lowercase hex of [bytes]. */
    fun encode(bytes: ByteArray): String = "0x" + bytes.joinToString("") { "%02x".format(it) }

    /** Decode a hex string (with or without `0x`); rejects odd length / non-hex. */
    fun decode(hex: String): ByteArray {
        val clean = hex.removePrefix("0x").removePrefix("0X")
        require(clean.length % 2 == 0) { "hex string has odd length: ${hex.length}" }
        return ByteArray(clean.length / 2) { i ->
            val hi = Character.digit(clean[i * 2], HEX_RADIX)
            val lo = Character.digit(clean[i * 2 + 1], HEX_RADIX)
            require(hi >= 0 && lo >= 0) { "invalid hex character in: $hex" }
            ((hi shl HEX_SHIFT) or lo).toByte()
        }
    }

    /** Left-pad [bytes] to a 32-byte EIP-712 word (right-aligned); rejects > 32 bytes. */
    fun toWord(bytes: ByteArray): ByteArray {
        require(bytes.size <= WORD_BYTES) { "value wider than a 32-byte word: ${bytes.size}" }
        if (bytes.size == WORD_BYTES) return bytes
        val word = ByteArray(WORD_BYTES)
        bytes.copyInto(word, WORD_BYTES - bytes.size)
        return word
    }

    /** A non-negative [value] as a 32-byte big-endian EIP-712 `uint256` word. */
    fun uint256(value: BigInteger): ByteArray {
        require(value.signum() >= 0) { "uint256 cannot be negative: $value" }
        // BigInteger.toByteArray may emit a leading sign byte; strip it before padding.
        val raw = value.toByteArray()
        val trimmed = if (raw.size > 1 && raw[0].toInt() == 0) raw.copyOfRange(1, raw.size) else raw
        return toWord(trimmed)
    }

    /** A 20-byte `address` (hex, `0x`-prefixed) as a left-padded 32-byte word. */
    fun address(hexAddress: String): ByteArray {
        val bytes = decode(hexAddress)
        require(bytes.size == ADDRESS_BYTES) { "address must be 20 bytes, got ${bytes.size}: $hexAddress" }
        return toWord(bytes)
    }

    private const val HEX_RADIX = 16
    private const val HEX_SHIFT = 4
    private const val ADDRESS_BYTES = 20
}
