package agents_engine.model

import agents_engine.generation.Generable
import agents_engine.generation.Guide
import agents_engine.generation.LenientJsonParser
import agents_engine.internal.toJsonString
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.time.Duration
import kotlin.time.toJavaDuration

/**
 * `agents_engine/model/PerplexitySearch.kt` — #3676, the `perplexitySearch`
 * tool (slice 2 of epic #3674).
 *
 * Lets an agent reasoning on its OWN model (Claude/OpenAI/Ollama/…) fetch
 * **web-grounded, cited** facts from Perplexity's Sonar API without switching
 * its own model to a sonar one. The tool is marked [ToolDef.untrustedOutput]
 * so the agentic loop wraps the result in the `{trusted:false}` envelope and
 * the system prompt warns the model to treat it as data, not instructions
 * (#642) — web search results are the canonical prompt-injection vector.
 *
 * Register on an agent via the `tools { }` DSL:
 * ```
 * tools { +perplexitySearchTool(apiKey = perplexityKey) }
 * ```
 *
 * The valuable part is the citations: the result's [PerplexitySearchResult]
 * renders the answer followed by a numbered source list, so the sources land
 * in both the model's context and the JSONL audit row as evidence.
 *
 * Search controls (model variant, recency/domain/mode filters, structured
 * output) are layered on in #3677 via [PerplexitySearchOptions].
 */

/** A `@Generable` single-field argument: the search query. */
@Generable("Arguments for a web-grounded Perplexity search")
data class PerplexitySearchArgs(
    @Guide("The natural-language search query to ground against live web sources")
    val query: String,
)

/** One grounded source returned by a Perplexity search (from `search_results[]` or `citations[]`). */
data class PerplexitySource(
    val url: String,
    val title: String? = null,
    val snippet: String? = null,
    val date: String? = null,
)

/**
 * Parsed result of a web-grounded search. [toString] renders the answer plus a
 * numbered source list — this is the text the agentic loop feeds back to the
 * model (then wraps in the untrusted envelope) and records in the audit row.
 */
data class PerplexitySearchResult(
    val answer: String,
    val sources: List<PerplexitySource>,
) {
    fun render(): String = buildString {
        append(answer.trim())
        if (sources.isNotEmpty()) {
            append("\n\nSources:")
            sources.forEachIndexed { i, s ->
                val label = s.title?.takeIf { it.isNotBlank() }
                append("\n[").append(i + 1).append("] ")
                if (label != null) append(label).append(" — ")
                append(s.url)
            }
        }
    }

    override fun toString(): String = render()
}

/**
 * Options controlling one search request. #3676 ships only [model]; #3677
 * extends this with `search_mode`, recency/domain filters, context size, and
 * structured output. Default model `sonar` (lightweight grounded search).
 */
data class PerplexitySearchOptions(
    val model: String = "sonar",
)

/**
 * The seam the tool calls — injectable so tests can return a canned result
 * without network. The default is [HttpPerplexitySearchBackend].
 */
fun interface PerplexitySearchBackend {
    fun search(query: String, options: PerplexitySearchOptions): PerplexitySearchResult
}

/** Raised when Perplexity returns an error envelope or a non-2xx status. */
class PerplexitySearchException(message: String) : RuntimeException(message)

/**
 * Build the minimal chat-completions request body for a grounded search.
 * Pure + internal so it is unit-testable without a live call. #3677 adds the
 * search-control fields here.
 */
internal fun buildPerplexitySearchBody(query: String, options: PerplexitySearchOptions): String =
    """{"model":${options.model.toJsonString()},"messages":[{"role":"user","content":${query.toJsonString()}}]}"""

/**
 * Parse a Perplexity chat-completions response body into a [PerplexitySearchResult].
 * Pure + internal so it is unit-testable without a live call.
 *
 * - `answer` ← `choices[0].message.content`.
 * - `sources` ← `search_results[]` (title/url/snippet/date) when present,
 *   else `citations[]` (URL strings) as a fallback.
 * - a top-level `error` envelope raises [PerplexitySearchException].
 */
internal fun parsePerplexitySearchResponse(rawJson: String): PerplexitySearchResult {
    val root = LenientJsonParser.parse(rawJson) as? Map<*, *>
        ?: throw PerplexitySearchException("Perplexity response was not a JSON object")

    (root["error"] as? Map<*, *>)?.let { err ->
        throw PerplexitySearchException("Perplexity error: ${err["message"] ?: err}")
    }

    val choices = root["choices"] as? List<*>
    val message = (choices?.firstOrNull() as? Map<*, *>)?.get("message") as? Map<*, *>
    val answer = (message?.get("content") as? String).orEmpty()

    val sources = parseSearchResults(root["search_results"])
        ?: parseCitations(root["citations"])
        ?: emptyList()

    return PerplexitySearchResult(answer = answer, sources = sources)
}

private fun parseSearchResults(node: Any?): List<PerplexitySource>? {
    val list = node as? List<*> ?: return null
    val sources = list.mapNotNull { item ->
        val obj = item as? Map<*, *> ?: return@mapNotNull null
        val url = (obj["url"] as? String) ?: (obj["URL"] as? String) ?: return@mapNotNull null
        PerplexitySource(
            url = url,
            title = obj["title"] as? String,
            snippet = obj["snippet"] as? String,
            date = obj["date"] as? String,
        )
    }
    return sources.ifEmpty { null }
}

private fun parseCitations(node: Any?): List<PerplexitySource>? {
    val list = node as? List<*> ?: return null
    val sources = list.mapNotNull { (it as? String)?.let { url -> PerplexitySource(url = url) } }
    return sources.ifEmpty { null }
}

/**
 * Default [PerplexitySearchBackend] — POSTs to `<baseUrl>/chat/completions`
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

/**
 * Build the `perplexity_search` tool. Register via `tools { +perplexitySearchTool(apiKey) }`.
 *
 * - `untrustedOutput = true` — results are auto-wrapped in the `{trusted:false}`
 *   envelope and the model is warned to treat them as data (#642).
 * - On a blank query or a backend failure, returns an `"ERROR: …"` string
 *   (the agentic loop's standard tool-error convention) rather than throwing.
 *
 * @param apiKey Perplexity API key (load from `.secrets/perplexity-key` / `PERPLEXITY_API_KEY`).
 * @param options default search options (model variant; extended in #3677).
 * @param backend override the network backend — injected in tests.
 */
fun perplexitySearchTool(
    apiKey: String,
    options: PerplexitySearchOptions = PerplexitySearchOptions(),
    baseUrl: String = PerplexityClient.DEFAULT_BASE_URL,
    backend: PerplexitySearchBackend = HttpPerplexitySearchBackend(apiKey, baseUrl),
): ToolDef = ToolDef(
    name = "perplexity_search",
    description = "Search the web for current, grounded facts and return an answer with citations. " +
        "Use for anything that may be recent, niche, or beyond your training data. Arguments: {query: string}.",
    argsType = PerplexitySearchArgs::class,
    untrustedOutput = true,
) { args ->
    val query = args["query"]?.toString().orEmpty()
    if (query.isBlank()) {
        "ERROR: missing 'query'"
    } else {
        runCatching { backend.search(query, options) }
            .getOrElse { e -> "ERROR: perplexity_search failed: ${e.message}" }
    }
}
