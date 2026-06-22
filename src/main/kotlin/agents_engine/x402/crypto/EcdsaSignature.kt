package agents_engine.x402.crypto

import java.math.BigInteger

/**
 * `agents_engine/x402/crypto/EcdsaSignature.kt` — #4528 (PRD §12.8). An ECDSA signature in Ethereum's packed
 * form: `r`, `s` (low-s normalized), and recovery byte `v ∈ {27, 28}`. Produced by [Secp256k1.sign] and
 * serialized to the 65-byte `r ‖ s ‖ v` hex an EIP-3009 `transferWithAuthorization` carries.
 */
internal data class EcdsaSignature(val r: BigInteger, val s: BigInteger, val v: Int) {
    /** The 65-byte `0x`-prefixed `r ‖ s ‖ v` hex an EIP-3009 authorization carries. */
    fun toHex(): String {
        val packed = Hex.uint256(r) + Hex.uint256(s) + byteArrayOf(v.toByte())
        return Hex.encode(packed)
    }
}
