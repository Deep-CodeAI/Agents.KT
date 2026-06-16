package agents_engine.agntcy.identity

import java.time.Instant

/**
 * `agents_engine/agntcy/identity/VerifiedBadge.kt` — #4521 (PRD §12.6). The result of a *successful*
 * [IdentityVerifier.verify]: an AGNTCY Identity agent badge whose JOSE/JWS signature has already been
 * validated against the issuer's JWKS. Construction implies cryptographic validity — there is no
 * "invalid" `VerifiedBadge` (failures throw [BadgeVerificationException]).
 *
 * @property issuer the credential issuer (`iss`) — the identity you are extending trust to.
 * @property subject the credential subject (`sub`) — typically the agent's unique id.
 * @property issuedAt `iat`, if present.
 * @property expiresAt `exp`, if present (already checked: an expired badge would not verify).
 * @property credentialSubject the W3C VC `vc.credentialSubject` claims (the agent's attributes), if present.
 * @property claims the full validated JWT claim set, for callers that need raw access.
 */
data class VerifiedBadge(
    val issuer: String?,
    val subject: String?,
    val issuedAt: Instant?,
    val expiresAt: Instant?,
    val credentialSubject: Map<String, Any?>,
    val claims: Map<String, Any?>,
)
