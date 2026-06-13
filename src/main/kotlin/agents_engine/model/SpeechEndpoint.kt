package agents_engine.model

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration

/**
 * #4504 — shared DX helpers for the HTTP speech adapters ([WhisperSttClient],
 * [QwenTtsClient]). Self-hosted endpoints are routinely "not running yet", so a
 * raw `ConnectException` is poor DX. These turn a down/slow server into an
 * actionable message and offer a cheap reachability probe for `preflight()`.
 */

/** Seconds the reachability probe waits before declaring an endpoint unreachable. */
private const val PROBE_TIMEOUT_SECONDS = 5L

/**
 * True when [baseUrl] answers at all — *any* HTTP status counts (a 404 on `/`
 * still proves the server is up). Only connection-level failures (refused, no
 * route, timeout) count as unreachable. Never throws.
 */
internal fun speechEndpointReachable(httpClient: HttpClient, baseUrl: String): Boolean =
    try {
        val request = HttpRequest.newBuilder(URI.create(baseUrl))
            .timeout(Duration.ofSeconds(PROBE_TIMEOUT_SECONDS))
            .GET()
            .build()
        httpClient.send(request, HttpResponse.BodyHandlers.discarding())
        true
    } catch (_: IOException) {
        false
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }

/**
 * Run an HTTP [block] against a speech endpoint, translating connection-level
 * failures into an actionable [IllegalStateException]. [what] names the operation
 * (e.g. "Whisper transcription"); [remediation] is the one-line "how to fix it".
 */
internal fun <T> withSpeechEndpoint(baseUrl: String, what: String, remediation: String, block: () -> T): T =
    try {
        block()
    } catch (e: HttpTimeoutException) {
        throw IllegalStateException(
            "$what timed out talking to $baseUrl — the server may still be loading the model. " +
                "Retry, or raise timeoutSeconds. ($remediation)",
            e,
        )
    } catch (e: java.net.ConnectException) {
        throw IllegalStateException("$what cannot reach a server at $baseUrl (${e.message}). $remediation", e)
    } catch (e: IOException) {
        throw IllegalStateException("$what failed talking to $baseUrl (${e.message}). $remediation", e)
    }
