package agents_engine.rag.springai

import agents_engine.rag.Embedded
import agents_engine.rag.EmbeddingStore
import agents_engine.rag.Filter
import agents_engine.rag.Match
import agents_engine.rag.Provenance
import agents_engine.rag.RagQuery
import agents_engine.rag.UpsertResult
import java.security.MessageDigest
import org.springframework.ai.document.Document
import org.springframework.ai.vectorstore.SearchRequest
import org.springframework.ai.vectorstore.VectorStore

/**
 * #3863 — adapts any Spring AI `VectorStore` (PgVector, Redis, Milvus,
 * `SimpleVectorStore`, …) to the Agents.KT RAG SPI.
 *
 * Spring AI stores embed **internally** (the `VectorStore` owns an
 * `EmbeddingModel`): upsert ignores `Embedded.embedding`, and queries use
 * `RagQuery.text` — no `Embedder` needed on `ragRetriever`. [Filter]s are
 * applied client-side on the returned documents' metadata; use Spring AI's
 * `FilterExpressionBuilder` on the delegate for server-side pushdown.
 * Provenance: `chunkId` = document id, `sourceUri` = `metadata["source"]`,
 * `hash` = SHA-256 of the document text.
 *
 * The delegate's blocking calls run on the caller's thread — dispatch
 * around the retriever if your store does network I/O.
 */
class SpringAiEmbeddingStore(
    private val delegate: VectorStore,
) : EmbeddingStore<String> {

    override suspend fun upsert(items: List<Embedded<String>>): UpsertResult {
        if (items.isEmpty()) return UpsertResult(0)
        val documents = items.map { item ->
            Document(item.id, item.value, item.metadata.mapValues { it.value as Any })
        }
        delegate.add(documents)
        return UpsertResult(items.size)
    }

    override suspend fun query(query: RagQuery, topK: Int, filter: Filter?): List<Match<String>> {
        val request = SearchRequest.builder()
            .query(query.text)
            .topK(topK)
            .build()
        val documents = delegate.similaritySearch(request) ?: emptyList()
        return documents
            .map { doc -> doc to doc.metadata.mapValues { it.value.toString() } }
            .filter { (_, metadata) -> filter == null || filter.matches(metadata) }
            .map { (doc, metadata) ->
                val text = doc.text.orEmpty()
                Match(
                    value = text,
                    score = doc.score?.toFloat() ?: 0f,
                    provenance = Provenance(
                        chunkId = doc.id,
                        sourceUri = metadata["source"],
                        hash = sha256(text),
                    ),
                )
            }
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
