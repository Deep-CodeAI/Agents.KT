package agents_engine.rag

import java.security.MessageDigest
import kotlin.math.sqrt

/**
 * #3863 — reference [EmbeddingStore] for tests and small corpora.
 * Cosine similarity over a synchronized in-heap map; upsert replaces by
 * id. Provenance per chunk: `chunkId` = the upserted id, `sourceUri` =
 * `metadata["source"]`, `hash` = SHA-256 of `value.toString()` computed
 * at upsert time. Not for production-sized corpora — everything lives
 * on the heap and queries are O(n).
 */
class InMemoryEmbeddingStore<T : Any> : EmbeddingStore<T> {

    private data class Stored<T : Any>(val item: Embedded<T>, val hash: String)

    private val items = LinkedHashMap<String, Stored<T>>()
    private val lock = Any()

    override suspend fun upsert(items: List<Embedded<T>>): UpsertResult {
        synchronized(lock) {
            items.forEach { item -> this.items[item.id] = Stored(item, sha256(item.value.toString())) }
        }
        return UpsertResult(items.size)
    }

    override suspend fun query(query: RagQuery, topK: Int, filter: Filter?): List<Match<T>> {
        val embedding = requireNotNull(query.embedding) {
            "InMemoryEmbeddingStore requires RagQuery.embedding — supply an Embedder to ragRetriever."
        }
        val snapshot = synchronized(lock) { items.values.toList() }
        return snapshot
            .filter { stored -> filter == null || filter.matches(stored.item.metadata) }
            .map { stored ->
                Match(
                    value = stored.item.value,
                    score = cosine(embedding.values, stored.item.embedding.values),
                    provenance = Provenance(
                        chunkId = stored.item.id,
                        sourceUri = stored.item.metadata["source"],
                        hash = stored.hash,
                    ),
                )
            }
            .sortedByDescending { it.score }
            .take(topK)
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "Embedding dimensions differ: query=${a.size}, stored=${b.size}." }
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom == 0f) 0f else dot / denom
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
