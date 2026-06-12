package agents_engine.core

/**
 * One knowledge source on a skill. Exactly one of [provider] (static,
 * inlined into the prompt and exposed as a no-arg knowledge tool) or
 * [retriever] (#3863 — query-aware, exposed as a knowledge tool taking
 * a `query` argument; never inlined) is the active content path.
 */
internal data class KnowledgeEntry(
    val description: String,
    val provider: () -> String,
    val retriever: KnowledgeRetriever? = null,
)
