package agents_engine.model

import agents_engine.generation.LenientJsonParser
import agents_engine.internal.toJsonString

/**
 * `agents_engine/model/PerplexitySearch.kt` — #3676 / #3677, the
 * `perplexitySearch` tool factory plus its pure request/response wire helpers.
 * The supporting types live one-per-file alongside (`PerplexitySearchArgs`,
 * `PerplexitySource`, `PerplexitySearchResult`, `PerplexitySearchOptions` +
 * `PerplexitySearchOptionsBuilder`, `PerplexitySearchBackend` +
 * `HttpPerplexitySearchBackend`, `PerplexitySearchException`, and the
 * `SearchMode` / `SearchRecency` / `SearchContextSize` enums).
 *
 * The tool lets an agent reasoning on its OWN model (Claude/OpenAI/Ollama/…)
 * fetch **web-grounded, cited** facts from Perplexity's Sonar API without
 * switching its own model to a sonar one. It is marked
 * [ToolDef.untrustedOutput] so the agentic loop wraps the result in the
 * `{trusted:false}` envelope and the system prompt warns the model to treat it
 * as data, not instructions (#642) — web search results are the canonical
 * prompt-injection vector. The result's [PerplexitySearchResult] renders the
 * answer followed by a numbered source list, so citations land in both the
 * model's context and the JSONL audit row as evidence.
 *
 * Register on an agent via the `tools { }` DSL:
 * ```
 * tools { +perplexitySearchTool(apiKey = perplexityKey) }
 * ```
 */

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
 * Build the `perplexity_search` tool. Register via `tools { +perplexitySearchTool(apiKey) }`.
 *
 * - `untrustedOutput = true` — results are auto-wrapped in the `{trusted:false}`
 *   envelope and the model is warned to treat them as data (#642).
 * - On a blank query or a backend failure, returns an `"ERROR: …"` string
 *   (the agentic loop's standard tool-error convention) rather than throwing.
 *
 * @param apiKey Perplexity API key (load from `.secrets/perplexity-key` / `PERPLEXITY_API_KEY`).
 * @param options default search options (model variant + #3677 controls).
 * @param baseUrl override the Perplexity base URL (proxies / regional endpoints).
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
