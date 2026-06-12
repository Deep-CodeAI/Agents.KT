package agents_engine.rag

/**
 * #3863 — where a retrieved chunk came from. Carried on every [Match] so
 * the audit trail (and the model context the retriever formats) can name
 * the exact source: reviewers trace `chunkId` + `hash` back to the corpus.
 */
data class Provenance(
    val chunkId: String,
    val sourceUri: String? = null,
    val hash: String? = null,
    val timestamp: String? = null,
)
