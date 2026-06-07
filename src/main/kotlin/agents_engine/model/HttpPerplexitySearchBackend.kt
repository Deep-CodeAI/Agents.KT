package agents_engine.model

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.time.Duration
import kotlin.time.toJavaDuration

/**
 * Default [PerplexitySearchBackend] (#3676) — POSTs to `<baseUrl>/chat/completions`
 * and parses the grounded answer + citations. Reuses the same JDK HttpClient
 * shape as [OpenAiClient].
 */
class HttpPerplexitySearchBackend(
    private val apiKey: String,
    private val baseUrl: String = PerplexityClient.DEFAULT_BASE_URL,
    private val requestTimeout: Duration = OpenAiClient.DEFAULT_REQUEST_TIMEOUT,
    connectTimeout: Duration = OpenAiClient.DEFAULT_CONNECT_TIMEOUT,
    httpClient: HttpClient? = null,
) : PerplexitySearchBackend {

    private val http: HttpClient = httpClient ?: HttpClient.newBuilder()
        .connectTimeout(connectTimeout.toJavaDuration())
        .build()

    override fun search(query: String, options: PerplexitySearchOptions): PerplexitySearchResult {
        val body = buildPerplexitySearchBody(query, options)
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/chat/completions"))
            .timeout(requestTimeout.toJavaDuration())
            .header("Authorization", "Bearer $apiKey")
            .header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() >= HTTP_BAD_REQUEST) {
            // Try to surface the provider's error message; fall back to the status line.
            val parsed = runCatching { parsePerplexitySearchResponse(response.body()) }
            parsed.exceptionOrNull()?.let { throw it }
            val snippet = response.body().take(ERROR_BODY_CAP)
            throw PerplexitySearchException("Perplexity HTTP ${response.statusCode()}: $snippet")
        }
        return parsePerplexitySearchResponse(response.body())
    }

    private companion object {
        const val HTTP_BAD_REQUEST = 400
        const val ERROR_BODY_CAP = 500
    }
}
