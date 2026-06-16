package agents_engine.agntcy.identity

import com.nimbusds.jose.jwk.JWKSet
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * `agents_engine/agntcy/identity/IdentityResolver.kt` — #4521 (PRD §12.6). The *resolve* half of badge
 * verification: fetch an AGNTCY issuer's public keys (`/.well-known/jwks.json`) so [IdentityVerifier.verify]
 * can validate a badge, and fetch the published credential / resolver metadata (`/.well-known/vcs.json`).
 *
 * Bounded by construction — connect/read timeouts and a response size cap — because these are network
 * reads of attacker-influenceable endpoints. Any failure surfaces as [BadgeVerificationException]; the
 * verifier still re-checks the signature, so a hostile JWKS host can at worst deny service, not forge trust.
 */
class IdentityResolver(
    private val connectTimeout: Duration = Duration.ofSeconds(5),
    private val readTimeout: Duration = Duration.ofSeconds(5),
    private val maxResponseBytes: Int = DEFAULT_MAX_RESPONSE_BYTES,
) {
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(connectTimeout).build()

    /** Fetch + parse the issuer JWKS at [jwksUrl] (typically `…/.well-known/jwks.json`). */
    fun fetchJwks(jwksUrl: String): JWKSet =
        try {
            JWKSet.parse(fetchText(jwksUrl))
        } catch (e: BadgeVerificationException) {
            throw e
        } catch (e: Exception) {
            throw BadgeVerificationException("failed to fetch/parse JWKS from $jwksUrl: ${e.message}", e)
        }

    /**
     * Fetch the raw JSON at [url] (e.g. `…/.well-known/vcs.json` or resolver metadata). Returned verbatim:
     * the AGNTCY VC envelope is still settling upstream, so the caller extracts the compact JWS to hand to
     * [IdentityVerifier.verify] rather than this binding it to a not-yet-stable schema.
     */
    fun fetchText(url: String): String {
        val req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(readTimeout)
            .header("Accept", "application/json")
            .GET().build()
        val resp = try {
            http.send(req, HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) {
            throw BadgeVerificationException("failed to fetch $url: ${e.message}", e)
        }
        if (resp.statusCode() !in 200..299) {
            throw BadgeVerificationException("fetch $url returned HTTP ${resp.statusCode()}")
        }
        val body = resp.body()
        if (body.toByteArray(Charsets.UTF_8).size > maxResponseBytes) {
            throw BadgeVerificationException("response from $url exceeds $maxResponseBytes bytes")
        }
        return body
    }

    companion object {
        const val DEFAULT_MAX_RESPONSE_BYTES: Int = 256 * 1024
    }
}
