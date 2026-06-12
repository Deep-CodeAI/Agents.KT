package agents_engine.rag

/**
 * #3863 — a retrieval query: the raw [text] plus an optional pre-computed
 * [embedding]. Both travel because stores differ in what they consume:
 * vector-native stores (the in-memory store, LangChain4j) require
 * [embedding]; stores that embed internally (Spring AI `VectorStore`)
 * use [text] and ignore [embedding]. A store that requires a missing
 * side fails loud rather than silently degrading.
 */
data class RagQuery(
    val text: String,
    val embedding: Embedding? = null,
)
