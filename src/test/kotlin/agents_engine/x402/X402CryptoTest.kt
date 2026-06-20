package agents_engine.x402

import agents_engine.x402.crypto.Eip712
import agents_engine.x402.crypto.Eip712Domain
import agents_engine.x402.crypto.Hex
import agents_engine.x402.crypto.Keccak256
import agents_engine.x402.crypto.Secp256k1
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals

// #4528 (PRD §12.8) — buyer-side crypto pinned against authoritative ethers.js vectors. If any of these
// drift, the EIP-712/EIP-3009 signature would be silently rejected by a real facilitator, so they are exact
// equality checks against values produced by ethers v6 (RFC 6979 deterministic ECDSA, low-s, recovery byte).
class X402CryptoTest {

    // privkey = 1 -> a well-known Ethereum address (independent of any library).
    private val pk1 = BigInteger.ONE
    private val pk1Address = "0x7e5f4552091a69125d5dfcb7b8c2659029395bdf"

    private val domain = Eip712Domain(
        name = "USD Coin",
        version = "2",
        chainId = 84_532L,
        verifyingContract = "0x036CbD53842c5426634e7929541eC2318f3dCF7e",
    )
    private val auth = PaymentAuthorization(
        from = pk1Address,
        to = "0x209693Bc6afc0C5328bA36FaF03C514EF312287C",
        value = BigInteger.valueOf(1000),
        validAfter = BigInteger.ZERO,
        validBefore = BigInteger.valueOf(1_750_000_000L),
        nonce = "0x0000000000000000000000000000000000000000000000000000000000000abc",
    )

    @Test
    fun `keccak256 is legacy Keccak, not SHA3-256`() {
        // keccak256("") — the canonical anchor that distinguishes Keccak padding from NIST SHA3.
        assertEquals(
            "0xc5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470",
            Hex.encode(Keccak256.hash(ByteArray(0))),
        )
    }

    @Test
    fun `EIP-3009 type hash matches the canonical constant`() {
        assertEquals(
            "0x7c7c6cdb67a18743f49ec6fa9b35f50d52ed05cbed4cc592e13b44501c1a2267",
            Hex.encode(Eip712.TRANSFER_WITH_AUTHORIZATION_TYPEHASH),
        )
    }

    @Test
    fun `address derivation matches the known privkey=1 address`() {
        assertEquals(pk1Address, Secp256k1.deriveAddress(pk1))
    }

    @Test
    fun `EIP-712 domain separator, struct hash and digest match ethers`() {
        assertEquals(
            "0x2f5ab5eec6c6d261a8ad2b303ae4ef05c8509de2250e072c3a2df0ad7f9f068b",
            Hex.encode(Eip712.domainSeparator(domain)),
        )
        assertEquals(
            "0x04f69a424c0faebdf18c3b398c929e7db46a88d4170f749e3a2525abde782937",
            Hex.encode(Eip712.structHash(auth)),
        )
        assertEquals(
            "0x5e5f597fb3f7ad1408b9a82cab9f5219846d79981c8f9fcb85b1a7f1d0404120",
            Hex.encode(Eip712.digest(domain, auth)),
        )
    }

    @Test
    fun `signature byte-for-byte matches ethers signTypedData`() {
        val digest = Eip712.digest(domain, auth)
        val signature = Secp256k1.sign(digest, pk1)
        // ethers v6 signTypedData over the same domain/message/key — deterministic, so this is exact.
        assertEquals(
            "0xbe32c1e29f83b84fdb851ff25d79b05e90277584b7d8d2a3d398b0d3afec279" +
                "9436b6db420c7825825093a470cb6da8abb13fd86f0b20f6d45adffbdb0441fca1c",
            signature.toHex(),
        )
        assertEquals(28, signature.v) // 0x1c
    }

    @Test
    fun `sign then ecrecover round-trips to the signer address`() {
        val digest = Eip712.digest(domain, auth)
        val signature = Secp256k1.sign(digest, pk1)
        assertEquals(pk1Address, Secp256k1.recoverAddress(digest, signature))
    }
}
