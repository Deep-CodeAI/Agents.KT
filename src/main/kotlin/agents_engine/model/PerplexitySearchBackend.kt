package agents_engine.model

/**
 * The seam the `perplexitySearch` tool calls (#3676) — injectable so tests can
 * return a canned result without network. The default is [HttpPerplexitySearchBackend].
 */
fun interface PerplexitySearchBackend {
    fun search(query: String, options: PerplexitySearchOptions): PerplexitySearchResult
}
