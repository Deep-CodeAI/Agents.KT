package agents_engine.core

data class KnowledgeTool(
    val name: String,
    val description: String,
    val call: () -> String,
    /**
     * #3863 — non-null for query-aware knowledge entries. The runtime
     * exposes these as tools taking a `query` argument and routes the
     * model's query here; [call] is the static path and must not be
     * invoked when this is set.
     */
    val retriever: KnowledgeRetriever? = null,
)
