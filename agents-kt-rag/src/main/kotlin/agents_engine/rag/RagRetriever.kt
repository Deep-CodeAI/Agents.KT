package agents_engine.rag

import agents_engine.core.KnowledgeRetriever

/**
 * `agents_engine/rag/RagRetriever.kt` — the bridge from the RAG SPI to
 * the core knowledge seam (#3863). `ragRetriever(store, embedder) { … }`
 * produces a [KnowledgeRetriever] usable directly in the skill DSL:
 *
 * ```kotlin
 * skill<String, String>("answer-from-docs", "Answers from project docs") {
 *     knowledge(
 *         "project-docs", "Product specs and ADRs",
 *         ragRetriever(pgvectorStore, openAiEmbedder) {
 *             topK = 8
 *             minScore = 0.55f
 *             filter { it["team"] == "platform" }
 *         },
 *     )
 *     tools()
 * }
 * ```
 *
 * The model sees a knowledge tool named after the entry taking a `query`
 * argument; each call embeds the query (when [embedder] is non-null),
 * queries the store, and renders matches with provenance lines
 * (`chunkId`, `source`, `hash`, score) so the audit trail and the model
 * context both carry where every fact came from.
 */
fun <T : Any> ragRetriever(
    store: EmbeddingStore<T>,
    embedder: Embedder? = null,
    block: RagOptionsBuilder.() -> Unit = {},
): KnowledgeRetriever {
    val options = RagOptionsBuilder().apply(block).build()
    return KnowledgeRetriever { query ->
        val ragQuery = RagQuery(text = query, embedding = embedder?.embed(query))
        val matches = store.query(ragQuery, options.topK, options.filter)
            .filter { it.score >= options.minScore }
        renderMatches(query, matches)
    }
}

private fun renderMatches(query: String, matches: List<Match<*>>): String {
    if (matches.isEmpty()) return "No knowledge matches for query: \"$query\"."
    return buildString {
        matches.forEachIndexed { index, match ->
            val p = match.provenance
            append("[${index + 1}] score=${"%.3f".format(match.score)} chunk=${p.chunkId}")
            p.sourceUri?.let { append(" source=$it") }
            p.hash?.let { append(" hash=${it.take(HASH_PREVIEW_CHARS)}") }
            appendLine()
            appendLine(match.value.toString())
        }
    }.trimEnd()
}

private const val HASH_PREVIEW_CHARS = 12
