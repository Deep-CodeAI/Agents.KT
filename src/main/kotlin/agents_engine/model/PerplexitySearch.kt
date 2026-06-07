package agents_engine.model

import agents_engine.generation.Generable
import agents_engine.generation.Guide
import agents_engine.generation.LenientJsonParser
import agents_engine.generation.hasGenerableAnnotation
import agents_engine.generation.jsonSchema
import agents_engine.internal.toJsonString
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.reflect.KClass
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

/** Perplexity `search_mode` — which corpus to ground against (#3677). */
enum class SearchMode { WEB, ACADEMIC, SEC }

/** Perplexity `search_recency_filter` — only consider sources newer than this (#3677). */
enum class SearchRecency { HOUR, DAY, WEEK, MONTH, YEAR }

/** Perplexity `web_search_options.search_context_size` — search depth/cost knob (#3677). */
enum class SearchContextSize { LOW, MEDIUM, HIGH }

/**
 * Options controlling one search request (#3676 + #3677). All controls beyond
 * [model] default to "unset" → omitted from the request, so a bare
 * `PerplexitySearchOptions()` produces the same plain `sonar` web search as
 * before (additive, backward-compatible). Build ergonomically with
 * [perplexitySearchOptions].
 *
 * @property model sonar variant: `sonar` / `sonar-pro` / `sonar-reasoning-pro` / `sonar-deep-research`.
 * @property mode `search_mode` — web / academic / sec.
 * @property recency `search_recency_filter` — hour / day / week / month / year.
 * @property domainsAllow `search_domain_filter` allow-list (bare domains).
 * @property domainsDeny `search_domain_filter` deny-list (serialized with a `-` prefix).
 * @property contextSize `web_search_options.search_context_size` — low / medium / high.
 * @property reasoningEffort `reasoning_effort` for reasoning / deep-research models
 *   (API also accepts `minimal`, not modeled by the shared [ReasoningEffort] enum).
 * @property jsonSchema native `response_format` json_schema — constrains the answer
 *   to a strict schema (set via [PerplexitySearchOptionsBuilder.structuredOutput]).
 */
data class PerplexitySearchOptions(
    val model: String = "sonar",
    val mode: SearchMode? = null,
    val recency: SearchRecency? = null,
    val domainsAllow: List<String> = emptyList(),
    val domainsDeny: List<String> = emptyList(),
    val contextSize: SearchContextSize? = null,
    val reasoningEffort: ReasoningEffort? = null,
    val jsonSchema: JsonSchema? = null,
)

/** Ergonomic DSL for [PerplexitySearchOptions] — `perplexitySearchOptions { mode = SearchMode.ACADEMIC; … }`. */
class PerplexitySearchOptionsBuilder {
    var model: String = "sonar"
    var mode: SearchMode? = null
    var recency: SearchRecency? = null
    var contextSize: SearchContextSize? = null
    var reasoningEffort: ReasoningEffort? = null
    var jsonSchema: JsonSchema? = null
    private val allow = mutableListOf<String>()
    private val deny = mutableListOf<String>()

    /** Restrict search to these domains (`search_domain_filter`). */
    fun allowDomains(vararg domains: String) { allow += domains }

    /** Exclude these domains (`search_domain_filter` entries prefixed with `-`). */
    fun denyDomains(vararg domains: String) { deny += domains }

    /**
     * Constrain the answer to a `@Generable` type's JSON schema via native
     * `response_format`. The answer comes back as JSON matching [type].
     */
    fun structuredOutput(type: KClass<*>) {
        require(type.hasGenerableAnnotation()) {
            "structuredOutput type ${type.simpleName} must be annotated with @Generable"
        }
        jsonSchema = JsonSchema(name = type.simpleName ?: "structured_output", schema = type.jsonSchema())
    }

    internal fun build(): PerplexitySearchOptions = PerplexitySearchOptions(
        model = model,
        mode = mode,
        recency = recency,
        domainsAllow = allow.toList(),
        domainsDeny = deny.toList(),
        contextSize = contextSize,
        reasoningEffort = reasoningEffort,
        jsonSchema = jsonSchema,
    )
}

/** Build [PerplexitySearchOptions] with the DSL builder. */
fun perplexitySearchOptions(block: PerplexitySearchOptionsBuilder.() -> Unit): PerplexitySearchOptions =
    PerplexitySearchOptionsBuilder().apply(block).build()

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
 * Build the chat-completions request body for a grounded search, including any
 * #3677 search controls. Pure + internal so it is unit-testable without a live
 * call. Unset controls are omitted, so a bare [PerplexitySearchOptions]
 * reproduces the original minimal `model` + `messages` body.
 */
internal fun buildPerplexitySearchBody(query: String, options: PerplexitySearchOptions): String {
    val fields = buildList {
        add(""""model":${options.model.toJsonString()}""")
        add(""""messages":[{"role":"user","content":${query.toJsonString()}}]""")
        options.mode?.let { add(""""search_mode":${it.name.lowercase().toJsonString()}""") }
        options.recency?.let { add(""""search_recency_filter":${it.name.lowercase().toJsonString()}""") }
        val domains = options.domainsAllow + options.domainsDeny.map { "-$it" }
        if (domains.isNotEmpty()) {
            add(""""search_domain_filter":[${domains.joinToString(",") { it.toJsonString() }}]""")
        }
        options.contextSize?.let {
            add(""""web_search_options":{"search_context_size":${it.name.lowercase().toJsonString()}}""")
        }
        options.reasoningEffort?.let { add(""""reasoning_effort":${it.name.lowercase().toJsonString()}""") }
        options.jsonSchema?.let {
            add(
                """"response_format":{"type":"json_schema","json_schema":""" +
                    """{"name":${it.wireName().toJsonString()},"schema":${it.schema},"strict":true}}""",
            )
        }
    }
    return "{${fields.joinToString(",")}}"
}

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
