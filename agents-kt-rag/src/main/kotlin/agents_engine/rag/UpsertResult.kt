package agents_engine.rag

/** #3863 — outcome of an [EmbeddingStore.upsert]: how many items were written. */
data class UpsertResult(val count: Int)
