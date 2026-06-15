package agents_engine.model

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.time.Duration
import kotlin.time.toJavaDuration

/**
 * Default [NlWebSearchBackend] (#4541) — POSTs to `<baseUrl>/ask` and parses the
 * schema.org result list. NLWeb endpoints are public, so there is no auth header.
 * Reuses the same JDK HttpClient shape as [HttpPerplexitySearchBackend].
 */
class HttpNlWebSearchBackend(
    private val baseUrl: String,
    private val requestTimeout: Duration = OpenAiClient.DEFAULT_REQUEST_TIMEOUT,
    connectTimeout: Duration = OpenAiClient.DEFAULT_CONNECT_TIMEOUT,
    httpClient: HttpClient? = null,
) : NlWebSearchBackend {

    private val http: HttpClient = httpClient ?: HttpClient.newBuilder()
        .connectTimeout(connectTimeout.toJavaDuration())
        .build()

    override fun search(query: String, options: NlWebSearchOptions): NlWebSearchResult {
        val body = buildNlWebAskBody(query, options)
        val request = HttpRequest.newBuilder()
            .uri(URI.create("${baseUrl.trimEnd('/')}/ask"))
            .timeout(requestTimeout.toJavaDuration())
            .header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() >= HTTP_BAD_REQUEST) {
            // Try to surface the endpoint's error message; fall back to the status line.
            val parsed = runCatching { parseNlWebResponse(response.body()) }
            parsed.exceptionOrNull()?.let { throw it }
            throw NlWebSearchException("NLWeb HTTP ${response.statusCode()}: ${response.body().take(ERROR_BODY_CAP)}")
        }
        return parseNlWebResponse(response.body())
    }

    private companion object {
        const val HTTP_BAD_REQUEST = 400
        const val ERROR_BODY_CAP = 500
    }
}
