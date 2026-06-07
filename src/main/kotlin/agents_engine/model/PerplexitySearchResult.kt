package agents_engine.model

/**
 * Parsed result of a web-grounded search (#3676). [toString] renders the answer
 * plus a numbered source list — this is the text the agentic loop feeds back to
 * the model (then wraps in the untrusted envelope) and records in the audit row.
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
