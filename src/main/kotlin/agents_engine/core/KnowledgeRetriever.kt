package agents_engine.core

/**
 * #3863 — query-aware knowledge source. Unlike the static
 * `knowledge(key, description) { content }` provider, a retriever is
 * called **with the model's query** at tool-invocation time, so the
 * content can be looked up on demand (RAG over an embedding store, a
 * search index, a database). Registered via
 * `Skill.knowledge(key, description, retriever)`; surfaced to the model
 * as a knowledge tool taking a single `query` argument.
 *
 * Implementations should return the retrieved content as model-ready
 * text, including any provenance the audit trail should carry (the
 * `:agents-kt-rag` module's retriever appends source/chunk/hash lines).
 */
fun interface KnowledgeRetriever {
    suspend fun retrieve(query: String): String
}
