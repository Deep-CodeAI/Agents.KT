package agents_engine.model

/**
 * One grounded source returned by a Perplexity search (#3676) — parsed from
 * `search_results[]` (rich) or `citations[]` (URL only).
 */
data class PerplexitySource(
    val url: String,
    val title: String? = null,
    val snippet: String? = null,
    val date: String? = null,
)
