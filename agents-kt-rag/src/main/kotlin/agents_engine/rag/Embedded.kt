package agents_engine.rag

/**
 * #3863 — one item to upsert into an [EmbeddingStore]: the typed value,
 * its vector, a stable id (re-upserting the same id replaces), and
 * free-form string metadata used by [Filter]s and provenance
 * (`metadata["source"]` becomes [Provenance.sourceUri] in the in-memory
 * store and the shipped adapters).
 */
data class Embedded<T : Any>(
    val value: T,
    val embedding: Embedding,
    val id: String,
    val metadata: Map<String, String> = emptyMap(),
)
