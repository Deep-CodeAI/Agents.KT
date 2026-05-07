package agents_engine.model

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * Fail-fast reachability check for an Ollama HTTP endpoint (#1132).
 *
 * Wire into [agents_engine.runtime.LiveShowBuilder.precheck] so the REPL aborts
 * at startup with a clear error instead of greeting the user and only failing
 * mid-turn behind the spinner:
 *
 * ```kotlin
 * LiveRunner.serve(captain, args) {
 *     prompt = "fib> "
 *     precheck = OllamaPreflight(host = "localhost", port = 11434)::check
 * }
 * ```
 *
 * The check sends `GET /api/tags` — Ollama's lightweight catalog endpoint — and
 * throws [LlmProviderException] on connection failure or non-2xx status. The
 * message always names `host:port` so the operator knows which endpoint is
 * misconfigured.
 */
class OllamaPreflight(
    private val host: String = "localhost",
    private val port: Int = 11434,
    private val connectTimeout: Duration = 2.seconds,
    private val requestTimeout: Duration = 3.seconds,
) {
    fun check() {
        val endpoint = "$host:$port"
        val client = HttpClient.newBuilder()
            .connectTimeout(connectTimeout.toJavaDuration())
            .build()
        val request = HttpRequest.newBuilder()
            .uri(URI.create("http://$endpoint/api/tags"))
            .timeout(requestTimeout.toJavaDuration())
            .GET()
            .build()

        val response = try {
            client.send(request, HttpResponse.BodyHandlers.discarding())
        } catch (e: IOException) {
            throw LlmProviderException(
                "cannot reach Ollama at $endpoint — ${e.message ?: e.javaClass.simpleName}",
                e,
            )
        }

        val status = response.statusCode()
        if (status !in 200..299) {
            throw LlmProviderException(
                "Ollama at $endpoint returned status $status from /api/tags",
            )
        }
    }
}
