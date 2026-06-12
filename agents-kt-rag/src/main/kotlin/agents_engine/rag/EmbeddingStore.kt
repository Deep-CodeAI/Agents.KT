package agents_engine.rag

/**
 * #3863 — the RAG SPI. Agents.KT does not own the embedding/storage
 * layer: this interface sits cleanly above any concrete store, and the
 * shipped implementations are thin — [InMemoryEmbeddingStore] for tests
 * and small corpora, plus the `:agents-kt-rag-langchain4j` and
 * `:agents-kt-rag-spring-ai` adapter modules. Community stores implement
 * these two methods.
 */
interface EmbeddingStore<T : Any> {
    /** Write [items]; an existing id is replaced (upsert semantics). */
    suspend fun upsert(items: List<Embedded<T>>): UpsertResult

    /**
     * Return up to [topK] matches for [query], best first, optionally
     * post-filtered by [filter] over chunk metadata.
     */
    suspend fun query(query: RagQuery, topK: Int, filter: Filter? = null): List<Match<T>>
}
