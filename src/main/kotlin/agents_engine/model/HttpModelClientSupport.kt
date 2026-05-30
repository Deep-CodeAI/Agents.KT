package agents_engine.model

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * `agents_engine/model/HttpModelClientSupport.kt` — #2792 shared
 * transport seam for the JDK-HttpClient-backed provider adapters
 * ([ClaudeClient], [OpenAiClient], [OllamaClient], and [DeepSeekClient]
 * via OpenAi). Each adapter copy-pasted the same bounded-read +
 * OOM-guard block; the duplication is concentrated here so a future
 * transport-layer improvement (retry strategy, distributed tracing
 * span propagation, mTLS) is a one-place edit.
 *
 * Scope is intentionally small for the 0.6.x line: just the
 * "POST a JSON body, read a bounded response, surface a provider-
 * tagged LlmProviderException on overflow" pattern. The per-adapter
 * `HttpClient` instances still live in each client (their
 * `connectTimeout` differs across providers because users can tune
 * it per `model { }` block); future passes can lift more shape here
 * if more providers join.
 */
internal object HttpModelClientSupport {

    /**
     * Sends [request] using [http], reads at most [maxResponseBytes]
     * (+1 sentinel) of the response body, and throws
     * [LlmProviderException] tagged with [providerLabel] when the
     * response would exceed the cap. Returns the UTF-8 decoded body.
     *
     * The +1 sentinel + post-read compare is intentional — `readNBytes(N)`
     * may legitimately return exactly N bytes for an N-byte response, so
     * we read one more than the cap to disambiguate "exactly at cap" from
     * "would have exceeded".
     */
    fun sendBounded(
        http: HttpClient,
        request: HttpRequest,
        providerLabel: String,
        maxResponseBytes: Long,
    ): String {
        val response = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
        val cap = maxResponseBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val bytes = response.body().use { it.readNBytes(cap + 1) }
        if (bytes.size > cap) {
            throw LlmProviderException(
                "$providerLabel response exceeded $maxResponseBytes bytes; aborting to prevent OOM",
            )
        }
        return String(bytes, Charsets.UTF_8)
    }
}
