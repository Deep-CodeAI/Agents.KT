package agents_engine.agntcy.identity

/**
 * `agents_engine/agntcy/identity/BadgeVerificationException.kt` — #4521 (PRD §12.6). Thrown by
 * [IdentityVerifier.verify] when a badge is **not** trustworthy: bad signature, unknown/missing key,
 * a disallowed or `none` algorithm, malformed JWS, or a failed temporal claim (expired / not-yet-valid).
 * Fail-closed: anything short of a fully validated signature is an exception, never a partial result.
 */
class BadgeVerificationException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
