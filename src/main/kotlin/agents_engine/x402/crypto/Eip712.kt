package agents_engine.x402.crypto

import agents_engine.x402.PaymentAuthorization
import java.math.BigInteger

/**
 * `agents_engine/x402/crypto/Eip712.kt` — #4528 (PRD §12.8). Builds the EIP-712 typed-data digest the x402
 * buyer signs: an [EIP-3009](https://eips.ethereum.org/EIPS/eip-3009) `TransferWithAuthorization` message
 * scoped to the payment token's [Eip712Domain].
 *
 * The digest is `keccak256(0x1901 ‖ domainSeparator ‖ structHash)`, where each `keccak256(abi.encode(...))`
 * lays static fields out as 32-byte words ([Hex.toWord]). Only the one struct this protocol uses is encoded —
 * no general EIP-712 encoder.
 */
internal object Eip712 {
    /** `keccak256("EIP712Domain(string name,string version,uint256 chainId,address verifyingContract)")`. */
    private val DOMAIN_TYPEHASH = Keccak256.hashUtf8(
        "EIP712Domain(string name,string version,uint256 chainId,address verifyingContract)",
    )

    /**
     * The EIP-3009 type hash (`0x7c7c6cdb…`) — `keccak256` of the `TransferWithAuthorization(address from,
     * address to, uint256 value, uint256 validAfter, uint256 validBefore, bytes32 nonce)` type string.
     */
    val TRANSFER_WITH_AUTHORIZATION_TYPEHASH: ByteArray = Keccak256.hashUtf8(
        "TransferWithAuthorization(address from,address to,uint256 value," +
            "uint256 validAfter,uint256 validBefore,bytes32 nonce)",
    )

    private const val PREFIX_19 = 0x19.toByte()
    private const val PREFIX_01 = 0x01.toByte()

    /** The 32-byte domain separator for [domain]. */
    fun domainSeparator(domain: Eip712Domain): ByteArray = Keccak256.hash(
        DOMAIN_TYPEHASH,
        Keccak256.hashUtf8(domain.name),
        Keccak256.hashUtf8(domain.version),
        Hex.uint256(BigInteger.valueOf(domain.chainId)),
        Hex.address(domain.verifyingContract),
    )

    /** The 32-byte struct hash for [auth] (`keccak256(abi.encode(TYPEHASH, fields…))`). */
    fun structHash(auth: PaymentAuthorization): ByteArray = Keccak256.hash(
        TRANSFER_WITH_AUTHORIZATION_TYPEHASH,
        Hex.address(auth.from),
        Hex.address(auth.to),
        Hex.uint256(auth.value),
        Hex.uint256(auth.validAfter),
        Hex.uint256(auth.validBefore),
        Hex.toWord(Hex.decode(auth.nonce)),
    )

    /** The signable EIP-712 digest: `keccak256(0x1901 ‖ domainSeparator ‖ structHash)`. */
    fun digest(domain: Eip712Domain, auth: PaymentAuthorization): ByteArray = Keccak256.hash(
        byteArrayOf(PREFIX_19, PREFIX_01),
        domainSeparator(domain),
        structHash(auth),
    )
}
