package agents_engine.rag

/** #3863 — DSL builder for [RagOptions]. */
class RagOptionsBuilder {
    var topK: Int = RagOptions.DEFAULT_TOP_K
    var minScore: Float = 0f
    private var filter: Filter? = null

    /** Keep only chunks whose metadata satisfies [predicate]. */
    fun filter(predicate: (metadata: Map<String, String>) -> Boolean) {
        filter = Filter(predicate)
    }

    internal fun build(): RagOptions = RagOptions(topK = topK, minScore = minScore, filter = filter)
}
