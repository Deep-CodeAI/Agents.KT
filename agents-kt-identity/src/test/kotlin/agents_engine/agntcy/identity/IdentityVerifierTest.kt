package agents_engine.agntcy.identity

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.PlainJWT
import com.nimbusds.jwt.SignedJWT
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

// #4521 (PRD §12.6) — AGNTCY Identity badge verify. Hermetic: mint badges with a freshly generated
// issuer key and assert the verifier accepts the genuine one and rejects every forgery class
// (tamper, expiry, alg=none, algorithm-confusion, wrong key, unknown kid). These negative cases ARE
// the value — a trust primitive that accepts a forgery is worse than none.
class IdentityVerifierTest {

    private val issuerKey: ECKey = ECKeyGenerator(Curve.P_256).keyID("issuer-1").generate()
    private val jwks = JWKSet(issuerKey.toPublicJWK())

    private fun mintBadge(
        kid: String? = "issuer-1",
        expiresInMillis: Long = 3_600_000,
        key: ECKey = issuerKey,
    ): String {
        val now = System.currentTimeMillis()
        val claims = JWTClaimsSet.Builder()
            .issuer("https://issuer.example")
            .subject("agent:catalog")
            .issueTime(Date(now))
            .expirationTime(Date(now + expiresInMillis))
            .claim("vc", mapOf("credentialSubject" to mapOf("id" to "agent:catalog", "role" to "retriever")))
            .build()
        val jwt = SignedJWT(JWSHeader.Builder(JWSAlgorithm.ES256).keyID(kid).build(), claims)
        jwt.sign(ECDSASigner(key))
        return jwt.serialize()
    }

    @Test
    fun `a genuine badge verifies and exposes its claims`() {
        val badge = IdentityVerifier.verify(mintBadge(), jwks)
        assertEquals("https://issuer.example", badge.issuer)
        assertEquals("agent:catalog", badge.subject)
        assertEquals("retriever", badge.credentialSubject["role"])
    }

    @Test
    fun `a tampered payload is rejected`() {
        val parts = mintBadge().split(".")
        // swap in a different (validly-encoded) payload, keep the original signature
        val forgedPayload = com.nimbusds.jose.util.Base64URL.encode(
            """{"iss":"https://attacker.example","sub":"agent:evil"}""",
        ).toString()
        val forged = "${parts[0]}.$forgedPayload.${parts[2]}"
        assertFailsWith<BadgeVerificationException> { IdentityVerifier.verify(forged, jwks) }
    }

    @Test
    fun `an expired badge is rejected`() {
        val expired = mintBadge(expiresInMillis = -60_000) // expired a minute ago
        assertFailsWith<BadgeVerificationException> { IdentityVerifier.verify(expired, jwks) }
    }

    @Test
    fun `a badge signed by a different key is rejected`() {
        val attackerKey = ECKeyGenerator(Curve.P_256).keyID("issuer-1").generate() // same kid, different key
        assertFailsWith<BadgeVerificationException> { IdentityVerifier.verify(mintBadge(key = attackerKey), jwks) }
    }

    @Test
    fun `an unknown kid is rejected`() {
        assertFailsWith<BadgeVerificationException> { IdentityVerifier.verify(mintBadge(kid = "unknown"), jwks) }
    }

    @Test
    fun `an unsecured alg=none token is rejected`() {
        val now = System.currentTimeMillis()
        val plain = PlainJWT(
            JWTClaimsSet.Builder().issuer("https://attacker.example").subject("agent:evil")
                .expirationTime(Date(now + 3_600_000)).build(),
        ).serialize()
        assertFailsWith<BadgeVerificationException> { IdentityVerifier.verify(plain, jwks) }
    }

    @Test
    fun `an HMAC token is rejected (algorithm confusion)`() {
        // Classic attack: sign HS256 using the public key bytes as the shared secret.
        val secret = ByteArray(32) { it.toByte() }
        val now = System.currentTimeMillis()
        val jwt = SignedJWT(
            JWSHeader.Builder(JWSAlgorithm.HS256).keyID("issuer-1").build(),
            JWTClaimsSet.Builder().issuer("https://attacker.example").subject("agent:evil")
                .expirationTime(Date(now + 3_600_000)).build(),
        )
        jwt.sign(MACSigner(secret))
        assertFailsWith<BadgeVerificationException> { IdentityVerifier.verify(jwt.serialize(), jwks) }
    }

    @Test
    fun `resolver fetches a JWKS that then verifies a badge`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val body = jwks.toString().toByteArray(Charsets.UTF_8) // public JWKS JSON
        server.createContext("/.well-known/jwks.json") { ex ->
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}/.well-known/jwks.json"
            val fetched = IdentityResolver().fetchJwks(url)
            val badge = IdentityVerifier.verify(mintBadge(), fetched)
            assertEquals("agent:catalog", badge.subject)
        } finally {
            server.stop(0)
        }
    }
}
