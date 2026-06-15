package agents_engine.model

/**
 * Options for the `nlwebSearch` tool (#4541). [site] restricts the query to a
 * configured site/namespace on the endpoint (NLWeb's `site` token); [mode]
 * selects list / summarize / generate. Both are optional — a bare instance asks
 * the whole endpoint in `LIST` mode.
 */
data class NlWebSearchOptions(
    val site: String? = null,
    val mode: NlWebMode = NlWebMode.LIST,
)
