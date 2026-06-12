package agents_engine.rag

/**
 * #3863 — turns text into an [Embedding]. Agents.KT does not ship an
 * embedding model; bring your provider's (or wrap one from LangChain4j /
 * Spring AI). Must be deterministic for the same input within a session —
 * retrieval quality depends on query and corpus sharing one embedding space.
 */
fun interface Embedder {
    suspend fun embed(text: String): Embedding
}
