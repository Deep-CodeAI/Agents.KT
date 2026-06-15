package agents_engine.model

/**
 * The seam the `nlwebSearch` tool calls (#4541) — injectable so tests can return
 * a canned result without network. The default is [HttpNlWebSearchBackend].
 */
fun interface NlWebSearchBackend {
    fun search(query: String, options: NlWebSearchOptions): NlWebSearchResult
}
