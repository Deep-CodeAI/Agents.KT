package agents_engine.rag

/** #3863 — retrieval controls; built via [RagOptionsBuilder] in `ragRetriever { }`. */
data class RagOptions(
    val topK: Int = DEFAULT_TOP_K,
    val minScore: Float = 0f,
    val filter: Filter? = null,
) {
    init {
        require(topK > 0) { "topK must be positive, was $topK." }
    }

    companion object {
        const val DEFAULT_TOP_K: Int = 8
    }
}
