package agents_engine.model

import agents_engine.generation.LenientJsonParser
import agents_engine.internal.toJsonString

/**
 * `agents_engine/model/NlWebSearch.kt` — #4541 (PRD §12.9), the `nlwebSearch`
 * tool factory plus its pure request/response wire helpers. Supporting types
 * live one-per-file alongside (`NlWebSearchArgs`, `NlWebMode`,
 * `NlWebSearchOptions`, `NlWebResult`, `NlWebSearchResult`, `NlWebSearchBackend`
 * + `HttpNlWebSearchBackend`, `NlWebSearchException`).
 *
 * [NLWeb](https://github.com/nlweb-ai/NLWeb) gives a website a natural-language
 * interface over its **schema.org-structured content**. This tool lets an agent
 * on its OWN model ask an NLWeb endpoint and fold the ranked, schema.org-typed
 * results into its context — the inbound, external-knowledge counterpart to
 * MCP-tools. It is marked [ToolDef.untrustedOutput] so the agentic loop wraps the
 * result in the `{trusted:false}` envelope and warns the model to treat fetched
 * web content as data, not instructions (#642).
 *
 * (Every NLWeb endpoint is also an MCP server, so an NLWeb `/mcp` URL is equally
 * consumable through the existing MCP client; this tool is the zero-wiring
 * `/ask`-over-HTTP path for an agent on any model.)
 *
 * Register on an agent via the `tools { }` DSL:
 * ```
 * tools { +nlwebSearchTool(baseUrl = "https://example.com") }
 * ```
 */

/**
 * Build the NLWeb `/ask` request body. Pure + internal so it is unit-testable
 * without a live call. Streaming is disabled so the response is a single JSON
 * blob; [NlWebSearchOptions.site] is omitted when null.
 */
internal fun buildNlWebAskBody(query: String, options: NlWebSearchOptions): String {
    val fields = buildList {
        add(""""query":${query.toJsonString()}""")
        options.site?.let { add(""""site":${it.toJsonString()}""") }
        add(""""mode":${options.mode.name.lowercase().toJsonString()}""")
        add(""""streaming":false""")
    }
    return "{${fields.joinToString(",")}}"
}

/**
 * Parse an NLWeb `/ask` response body into an [NlWebSearchResult]. Pure +
 * internal so it is unit-testable without a live call.
 *
 * - `results[]` ← each `{url, name, site, score, description, schema_object}`;
 *   `schemaType` is `schema_object.@type` when present.
 * - `answer` ← a top-level `summary` / `answer` (present in `SUMMARIZE` /
 *   `GENERATE` mode), else null.
 * - a top-level `error` raises [NlWebSearchException].
 */
internal fun parseNlWebResponse(rawJson: String): NlWebSearchResult {
    val root = LenientJsonParser.parse(rawJson) as? Map<*, *>
        ?: throw NlWebSearchException("NLWeb response was not a JSON object")

    root["error"]?.let { err ->
        val message = (err as? Map<*, *>)?.get("message") ?: err
        throw NlWebSearchException("NLWeb error: $message")
    }

    val queryId = root["query_id"] as? String
    val answer = (root["summary"] as? String) ?: (root["answer"] as? String)
    val results = (root["results"] as? List<*>).orEmpty().mapNotNull { parseNlWebResult(it) }
    return NlWebSearchResult(results = results, answer = answer, queryId = queryId)
}

private fun parseNlWebResult(item: Any?): NlWebResult? {
    val obj = item as? Map<*, *> ?: return null
    val url = obj["url"] as? String ?: return null
    val schemaType = (obj["schema_object"] as? Map<*, *>)?.get("@type") as? String
    return NlWebResult(
        url = url,
        name = obj["name"] as? String,
        site = obj["site"] as? String,
        score = (obj["score"] as? Number)?.toDouble(),
        description = obj["description"] as? String,
        schemaType = schemaType,
    )
}

/**
 * Build the `nlweb_search` tool. Register via `tools { +nlwebSearchTool(baseUrl) }`.
 *
 * - `untrustedOutput = true` — results are auto-wrapped in the `{trusted:false}`
 *   envelope and the model is warned to treat them as data (#642).
 * - On a blank query or a backend failure, returns an `"ERROR: …"` string
 *   (the agentic loop's standard tool-error convention) rather than throwing.
 *
 * @param baseUrl the NLWeb endpoint base URL (e.g. `http://localhost:8000`); `/ask` is appended.
 * @param options default query options (`site` namespace + list/summarize/generate `mode`).
 * @param backend override the network backend — injected in tests.
 */
fun nlwebSearchTool(
    baseUrl: String,
    options: NlWebSearchOptions = NlWebSearchOptions(),
    backend: NlWebSearchBackend = HttpNlWebSearchBackend(baseUrl),
): ToolDef = ToolDef(
    name = "nlweb_search",
    description = "Query an NLWeb endpoint — a website's natural-language interface — for schema.org-" +
        "structured answers from its content (its catalog, articles, recipes, etc.). Arguments: {query: string}.",
    argsType = NlWebSearchArgs::class,
    untrustedOutput = true,
) { args ->
    val query = args["query"]?.toString().orEmpty()
    if (query.isBlank()) {
        "ERROR: missing 'query'"
    } else {
        runCatching { backend.search(query, options) }
            .getOrElse { e -> "ERROR: nlweb_search failed: ${e.message}" }
    }
}
