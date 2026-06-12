package agents_engine.rag.langchain4j

import agents_engine.rag.Embedded
import agents_engine.rag.EmbeddingStore
import agents_engine.rag.Filter
import agents_engine.rag.Match
import agents_engine.rag.Provenance
import agents_engine.rag.RagQuery
import agents_engine.rag.UpsertResult
import dev.langchain4j.data.document.Metadata
import dev.langchain4j.data.segment.TextSegment
import dev.langchain4j.store.embedding.EmbeddingSearchRequest
import java.security.MessageDigest

/**
 * #3863 — adapts any LangChain4j `EmbeddingStore<TextSegment>` (PgVector,
 * Elasticsearch, Chroma, the in-memory store, …) to the Agents.KT RAG SPI.
 *
 * Vector-native: queries require `RagQuery.embedding` — supply an
 * `Embedder` to `ragRetriever`. [Filter]s are applied client-side on the
 * returned segments' metadata; use the delegate's native filter API for
 * server-side pushdown. Provenance: `chunkId` = LangChain4j embedding id,
 * `sourceUri` = `metadata["source"]`, `hash` = SHA-256 of the segment text.
 *
 * The delegate's blocking calls run on the caller's thread — dispatch
 * around the retriever if your store does network I/O.
 */
class LangChain4jEmbeddingStore(
    private val delegate: dev.langchain4j.store.embedding.EmbeddingStore<TextSegment>,
) : EmbeddingStore<String> {

    override suspend fun upsert(items: List<Embedded<String>>): UpsertResult {
        if (items.isEmpty()) return UpsertResult(0)
        val ids = items.map { it.id }
        val embeddings = items.map { dev.langchain4j.data.embedding.Embedding(it.embedding.values) }
        val segments = items.map { TextSegment.from(it.value, Metadata.from(it.metadata)) }
        delegate.addAll(ids, embeddings, segments)
        return UpsertResult(items.size)
    }

    override suspend fun query(query: RagQuery, topK: Int, filter: Filter?): List<Match<String>> {
        val embedding = requireNotNull(query.embedding) {
            "LangChain4jEmbeddingStore requires RagQuery.embedding — supply an Embedder to ragRetriever."
        }
        val request = EmbeddingSearchRequest.builder()
            .queryEmbedding(dev.langchain4j.data.embedding.Embedding(embedding.values))
            .maxResults(topK)
            .build()
        return delegate.search(request).matches()
            .map { match ->
                val segment = match.embedded()
                val metadata = segment.metadata().toMap().mapValues { it.value.toString() }
                Triple(match, segment, metadata)
            }
            .filter { (_, _, metadata) -> filter == null || filter.matches(metadata) }
            .map { (match, segment, metadata) ->
                Match(
                    value = segment.text(),
                    score = match.score().toFloat(),
                    provenance = Provenance(
                        chunkId = match.embeddingId(),
                        sourceUri = metadata["source"],
                        hash = sha256(segment.text()),
                    ),
                )
            }
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
