package agents_engine.rag

/** #3863 — one retrieval hit: the typed value, its similarity score, and [Provenance]. */
data class Match<T : Any>(
    val value: T,
    val score: Float,
    val provenance: Provenance,
)
