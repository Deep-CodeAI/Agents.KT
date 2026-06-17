package agents_engine.model

import java.io.IOException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException

/**
 * `agents_engine/model/HttpModelClientSupport.kt` — #2792 shared transport seam for the
 * JDK-HttpClient-backed provider adapters ([ClaudeClient], [OpenAiClient] (+ DeepSeek/Kimi/OpenRouter/
 * Perplexity via it), [GeminiClient], [OllamaClient]). Concentrates the bounded-read + OOM-guard and —
 * since #4560 — the **transient-network retry policy**, so transport-layer improvements are one-place edits.
 *
 * **Transient retry (#4560).** [sendBounded] retries the *non-streaming* request on:
 * - **connection-level exceptions** — an [IOException] from `http.send` (connection reset, refused,
 *   no-route, unexpected EOF), and
 * - **transient HTTP statuses** — 408 / 429 / 500 / 502 / 503 / 504.
 *
 * Up to [MAX_ATTEMPTS] tries with exponential backoff (`INITIAL_BACKOFF_MS * 2^attempt`). This matches the
 * behavior official SDKs (e.g. OpenAI) apply by default — a dropped connection or a 503 is the textbook
 * retryable case, so callers get it without opting in.
 *
 * Two deliberate exclusions:
 * - **[HttpTimeoutException] is NOT retried** — the per-request `timeout` is the caller's *total* budget;
 *   retrying would silently multiply it. It propagates immediately, fast and raw.
 * - **the original exception type is preserved** on exhaustion (rethrown as-is, not wrapped), so the
 *   agent-level `onLLMError`/[LlmErrorDecision] handler can still pattern-match `e is ConnectException` etc.
 *
 * It sits **below** `onLLMError`: the transport rides out blips first; a persistent failure surfaces to the
 * handler with its identity intact. On the final attempt a transient *status* is NOT converted to an error
 * here — the body is returned unchanged so the per-provider parser still surfaces the provider's own message.
 *
 * Streaming (`sendChatStream`) is deliberately not retried here — re-issuing mid-stream would duplicate
 * already-delivered tokens; connect-phase streaming retry is a separate follow-up.
 */
internal object HttpModelClientSupport {

    const val MAX_ATTEMPTS: Int = 3
    const val INITIAL_BACKOFF_MS: Long = 250L
    // 408 Request Timeout, 429 Too Many Requests, 500/502/503/504 server/gateway errors.
    @Suppress("MagicNumber")
    private val TRANSIENT_STATUSES: Set<Int> = setOf(408, 429, 500, 502, 503, 504)

    /**
     * Sends [request] using [http] (with transient retry, see class doc), reads at most [maxResponseBytes]
     * (+1 sentinel) of the response body, and throws [LlmProviderException] tagged with [providerLabel] when
     * the response would exceed the cap. Returns the UTF-8 decoded body.
     */
    fun sendBounded(
        http: HttpClient,
        request: HttpRequest,
        providerLabel: String,
        maxResponseBytes: Long,
    ): String {
        repeat(MAX_ATTEMPTS) { attempt ->
            val lastAttempt = attempt == MAX_ATTEMPTS - 1
            val response: HttpResponse<java.io.InputStream> = try {
                http.send(request, HttpResponse.BodyHandlers.ofInputStream())
            } catch (e: HttpTimeoutException) {
                throw e // the per-request timeout is the caller's total budget — never multiply it by retrying
            } catch (e: IOException) {
                // Connection-level failure (reset, refused, no route, unexpected EOF). Rethrow the ORIGINAL
                // on exhaustion so onLLMError can still match `e is ConnectException`, etc.
                if (lastAttempt) throw e
                backoff(attempt)
                return@repeat
            }
            // Transient server/throttling status: retry unless we're out of attempts. On the final attempt
            // fall through and return the body so the per-provider parser surfaces the real error message.
            if (!lastAttempt && response.statusCode() in TRANSIENT_STATUSES) {
                response.body().close()
                backoff(attempt)
                return@repeat
            }
            return readBounded(response, providerLabel, maxResponseBytes)
        }
        error("sendBounded retry loop exited without a result") // unreachable: last attempt returns or throws
    }

    private fun readBounded(
        response: HttpResponse<java.io.InputStream>,
        providerLabel: String,
        maxResponseBytes: Long,
    ): String {
        val cap = maxResponseBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        // +1 sentinel: readNBytes(N) may return exactly N for an N-byte body, so read one more to
        // disambiguate "exactly at cap" from "would have exceeded".
        val bytes = response.body().use { it.readNBytes(cap + 1) }
        if (bytes.size > cap) {
            throw LlmProviderException(
                "$providerLabel response exceeded $maxResponseBytes bytes; aborting to prevent OOM",
            )
        }
        return String(bytes, Charsets.UTF_8)
    }

    private fun backoff(attempt: Int) {
        Thread.sleep(INITIAL_BACKOFF_MS shl attempt)
    }
}
