package agents_engine.model

/**
 * Parsed result of an NLWeb `/ask` query (#4541). [render] formats the ranked
 * matches into the text the agentic loop feeds back to the model (then wraps in
 * the untrusted envelope) and records in the audit row — schema.org results from
 * a website are external content, so they are treated as data, not instructions.
 * [answer] holds the LLM-composed reply when the endpoint ran in
 * `SUMMARIZE` / `GENERATE` mode (null in `LIST` mode).
 */
data class NlWebSearchResult(
    val results: List<NlWebResult>,
    val answer: String? = null,
    val queryId: String? = null,
) {
    fun render(): String = buildString {
        answer?.takeIf { it.isNotBlank() }?.let { append(it.trim()).append("\n\n") }
        if (results.isEmpty()) {
            if (answer.isNullOrBlank()) append("No results.")
        } else {
            append("Results:")
            results.forEachIndexed { i, r ->
                append("\n[").append(i + 1).append("] ")
                r.name?.takeIf { it.isNotBlank() }?.let { append(it) }
                r.schemaType?.takeIf { it.isNotBlank() }?.let { append(" (").append(it).append(")") }
                r.description?.takeIf { it.isNotBlank() }?.let { append(" — ").append(it.trim()) }
                append("\n    ").append(r.url)
            }
        }
    }

    override fun toString(): String = render()
}
