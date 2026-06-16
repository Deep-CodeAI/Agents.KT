package agents_engine.agntcy.identity

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import com.nimbusds.jose.proc.JWSVerificationKeySelector
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jwt.proc.DefaultJWTProcessor

/**
 * `agents_engine/agntcy/identity/IdentityVerifier.kt` — #4521 (PRD §12.6). Verifies an
 * [AGNTCY Identity](https://docs.agntcy.org/identity/) agent **badge** — a W3C Verifiable Credential
 * secured with JOSE/JWS — against an issuer's JWKS (`/.well-known/jwks.json`). The trust pillar of the
 * AGNTCY epic (#4517), beside the OASF discovery record (§12.6) and A2A invocation (§12.5).
 *
 * **Verify-only.** Issuance (key management, signing, vaults) is the heavy half and is deferred to the
 * self-hosted stack (PRD §12.6). This is the cheap, high-value half: in a trust-gated network you accept
 * work only from agents whose badge a known issuer signed.
 *
 * **Fail-closed and not hand-rolled.** Signature verification is trust-critical, so this delegates to the
 * vetted [nimbus-jose-jwt](https://connect2id.com/products/nimbus-jose-jwt) processor rather than parsing
 * JWS by hand. The key selector only admits the configured asymmetric algorithms, so `alg: none` and
 * algorithm-confusion (verifying an HMAC `HS*` token with a public key) are rejected; `exp`/`nbf` are
 * checked by the default claims verifier. Anything short of a fully validated signature throws
 * [BadgeVerificationException] — there is no partial success.
 *
 * ```kotlin
 * val jwks = IdentityResolver().fetchJwks("https://issuer.example/.well-known/jwks.json")
 * val badge = IdentityVerifier.verify(compactJws, jwks) // throws if untrustworthy
 * // badge.issuer / badge.subject / badge.credentialSubject are now safe to trust
 * ```
 */
object IdentityVerifier {

    /**
     * The asymmetric signature algorithms a badge may use. Deliberately excludes `none` and all symmetric
     * (`HS*`) algorithms — admitting an `HS*` would let an attacker forge a token using the public JWKS as
     * the HMAC secret (the classic algorithm-confusion attack).
     */
    val DEFAULT_ALGORITHMS: Set<JWSAlgorithm> = setOf(
        JWSAlgorithm.ES256, JWSAlgorithm.ES384, JWSAlgorithm.ES512,
        JWSAlgorithm.RS256, JWSAlgorithm.RS384, JWSAlgorithm.RS512,
        JWSAlgorithm.PS256, JWSAlgorithm.PS384, JWSAlgorithm.PS512,
        JWSAlgorithm.EdDSA,
    )

    /**
     * Verify [compactJws] (an AGNTCY badge as a compact JWS / VC-JWT) against [jwks], the issuer's key set
     * (the key is selected by the JWS `kid`). Returns the validated [VerifiedBadge]; throws
     * [BadgeVerificationException] on any failure. [allowedAlgorithms] defaults to [DEFAULT_ALGORITHMS].
     */
    fun verify(
        compactJws: String,
        jwks: JWKSet,
        allowedAlgorithms: Set<JWSAlgorithm> = DEFAULT_ALGORITHMS,
    ): VerifiedBadge {
        require(allowedAlgorithms.isNotEmpty()) { "allowedAlgorithms must not be empty" }
        val processor = DefaultJWTProcessor<SecurityContext>().apply {
            jwsKeySelector = JWSVerificationKeySelector(allowedAlgorithms, ImmutableJWKSet(jwks))
        }
        val claims = try {
            processor.process(compactJws, null)
        } catch (e: Exception) {
            throw BadgeVerificationException("badge verification failed: ${e.message}", e)
        }

        @Suppress("UNCHECKED_CAST")
        val vc = claims.getClaim("vc") as? Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val credentialSubject = (vc?.get("credentialSubject") as? Map<String, Any?>) ?: emptyMap()

        return VerifiedBadge(
            issuer = claims.issuer,
            subject = claims.subject,
            issuedAt = claims.issueTime?.toInstant(),
            expiresAt = claims.expirationTime?.toInstant(),
            credentialSubject = credentialSubject,
            claims = claims.claims,
        )
    }
}
