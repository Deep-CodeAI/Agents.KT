package agents_engine.x402.crypto

import java.math.BigInteger
import org.bouncycastle.asn1.sec.SECNamedCurves
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.signers.ECDSASigner
import org.bouncycastle.crypto.signers.HMacDSAKCalculator
import org.bouncycastle.math.ec.ECAlgorithms
import org.bouncycastle.math.ec.ECPoint

/**
 * `agents_engine/x402/crypto/Secp256k1.kt` — #4528 (PRD §12.8). The Ethereum signing primitives the x402
 * buyer needs: derive an EOA address from a private key, and produce an EIP-712 ECDSA signature over a 32-byte
 * digest as packed `r ‖ s ‖ v` (the 65-byte form an EIP-3009 `transferWithAuthorization` carries).
 *
 * Deterministic `k` per **RFC 6979** (no RNG — same digest+key always yields the same signature), **low-s**
 * normalization (EIP-2 / the malleability rule chains enforce), and the recovery byte `v ∈ {27, 28}` computed
 * by trying both recovery ids and matching the recovered point back to the signer's public key.
 *
 * All on BouncyCastle's `secp256k1` curve — no `web3j`/`kethereum` dependency.
 */
internal object Secp256k1 {
    private val params = SECNamedCurves.getByName("secp256k1")
    private val curve: ECDomainParameters =
        ECDomainParameters(params.curve, params.g, params.n, params.h)
    private val halfN: BigInteger = curve.n.shiftRight(1)

    private const val COORD_BYTES = 32
    private const val ADDRESS_BYTES = 20
    private const val V_BASE = 27

    /** The public key point for [privateKey] (`d · G`). */
    private fun publicPoint(privateKey: BigInteger): ECPoint = curve.g.multiply(privateKey).normalize()

    /** The 64-byte uncompressed public key (X ‖ Y, no `0x04` prefix) for [privateKey]. */
    fun publicKeyBytes(privateKey: BigInteger): ByteArray {
        val q = publicPoint(privateKey)
        val x = unsigned32(q.affineXCoord.toBigInteger())
        val y = unsigned32(q.affineYCoord.toBigInteger())
        return x + y
    }

    /** The 20-byte Ethereum address (`0x…`) for [privateKey] = `keccak256(pubKeyXY)[12:]`. */
    fun deriveAddress(privateKey: BigInteger): String {
        val hash = Keccak256.hash(publicKeyBytes(privateKey))
        return Hex.encode(hash.copyOfRange(hash.size - ADDRESS_BYTES, hash.size))
    }

    /** A signature over [messageHash] (a 32-byte digest) by [privateKey], as packed `r ‖ s ‖ v` (65 bytes). */
    fun sign(messageHash: ByteArray, privateKey: BigInteger): EcdsaSignature {
        require(messageHash.size == COORD_BYTES) { "message hash must be 32 bytes, got ${messageHash.size}" }
        val signer = ECDSASigner(HMacDSAKCalculator(SHA256Digest()))
        signer.init(true, ECPrivateKeyParameters(privateKey, curve))
        val rs = signer.generateSignature(messageHash)
        val r = rs[0]
        val s = lowS(rs[1])
        val v = recoveryId(r, s, messageHash, publicPoint(privateKey)) + V_BASE
        return EcdsaSignature(r, s, v)
    }

    /** Enforce low-s (EIP-2): if `s > n/2`, replace with `n - s`. */
    private fun lowS(s: BigInteger): BigInteger = if (s > halfN) curve.n.subtract(s) else s

    /** Find the recovery id (0/1) whose recovered point equals [expected]. */
    private fun recoveryId(r: BigInteger, s: BigInteger, messageHash: ByteArray, expected: ECPoint): Int {
        for (recId in 0..1) {
            if (recoverPoint(recId, r, s, messageHash) == expected) return recId
        }
        error("could not compute a recovery id for the signature")
    }

    /**
     * The address that produced [signature] over [messageHash] — `ecrecover`. Used to round-trip-verify
     * signing in tests (sign → recover → assert it matches the signer's address).
     */
    fun recoverAddress(messageHash: ByteArray, signature: EcdsaSignature): String {
        val point = recoverPoint(signature.v - V_BASE, signature.r, signature.s, messageHash)
            ?: error("signature did not recover a public key")
        val x = unsigned32(point.affineXCoord.toBigInteger())
        val y = unsigned32(point.affineYCoord.toBigInteger())
        val hash = Keccak256.hash(x + y)
        return Hex.encode(hash.copyOfRange(hash.size - ADDRESS_BYTES, hash.size))
    }

    /** Recover the candidate public-key point for recovery id [recId] (standard ECDSA pubkey recovery). */
    @Suppress("ReturnCount")
    private fun recoverPoint(recId: Int, r: BigInteger, s: BigInteger, messageHash: ByteArray): ECPoint? {
        val n = curve.n
        val x = r.add(BigInteger.valueOf((recId / 2).toLong()).multiply(n))
        val prime = (curve.curve as org.bouncycastle.math.ec.ECCurve.AbstractFp).q
        if (x >= prime) return null
        val rPoint = decompressPoint(x, recId and 1) ?: return null
        if (!rPoint.multiply(n).isInfinity) return null
        val e = BigInteger(1, messageHash)
        val rInv = r.modInverse(n)
        val srInv = rInv.multiply(s).mod(n)
        val erInv = rInv.multiply(e).mod(n).negate().mod(n)
        return ECAlgorithms.sumOfTwoMultiplies(curve.g, erInv, rPoint, srInv).normalize()
    }

    private fun decompressPoint(x: BigInteger, yBit: Int): ECPoint? = runCatching {
        val compressed = ByteArray(COORD_BYTES + 1)
        compressed[0] = (0x02 or yBit).toByte()
        unsigned32(x).copyInto(compressed, 1)
        curve.curve.decodePoint(compressed)
    }.getOrNull()

    /** A field element as exactly 32 unsigned big-endian bytes (strip sign byte / left-pad). */
    private fun unsigned32(value: BigInteger): ByteArray {
        val raw = value.toByteArray()
        return when {
            raw.size == COORD_BYTES -> raw
            raw.size == COORD_BYTES + 1 && raw[0].toInt() == 0 -> raw.copyOfRange(1, raw.size)
            raw.size < COORD_BYTES -> ByteArray(COORD_BYTES - raw.size) + raw
            else -> raw.copyOfRange(raw.size - COORD_BYTES, raw.size)
        }
    }
}
