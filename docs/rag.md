# RAG — EmbeddingStore SPI + query-aware knowledge (#3863)

Agents.KT does **not** own the embedding/storage layer — the positioning is "not a vector store," not "no RAG ergonomics." The `:agents-kt-rag` module ships a minimal SPI that sits above any concrete store, two adapter modules, and a bridge into the skill DSL's knowledge seam. You bring the store and the embedding model; the framework gives you a typed retrieval surface that respects the tool allowlist and carries provenance into the audit trail.

> **Publication status:** `:agents-kt-rag`, `:agents-kt-rag-langchain4j`, and `:agents-kt-rag-spring-ai` are in-repo modules — consume via `implementation(project(":agents-kt-rag"))` from this repository. Only `agents-kt` and `agents-kt-ksp` are on Maven Central today.

## The SPI

```kotlin
interface EmbeddingStore<T : Any> {
    suspend fun upsert(items: List<Embedded<T>>): UpsertResult
    suspend fun query(query: RagQuery, topK: Int, filter: Filter? = null): List<Match<T>>
}

fun interface Embedder { suspend fun embed(text: String): Embedding }
```

- `Embedded<T>` — value + vector + stable id (+ string metadata; `metadata["source"]` feeds provenance).
- `RagQuery` — the raw query text **plus** an optional pre-computed embedding. Both travel because stores differ: vector-native stores (in-memory, LangChain4j) require the embedding; stores that embed internally (Spring AI) use the text. A store missing the side it needs fails loud.
- `Match<T>` — value, score, and `Provenance { chunkId, sourceUri, hash, timestamp }` so reviewers can trace every retrieved chunk back to the corpus.
- `Filter` — a metadata predicate, applied client-side by the shipped adapters. Need server-side pushdown? Use the underlying store's native filter API.
- `InMemoryEmbeddingStore<T>` — cosine-similarity reference implementation for tests and small corpora.

## Wiring it into a skill

`ragRetriever(store, embedder) { … }` produces a `KnowledgeRetriever` — the core's query-aware knowledge seam:

```kotlin
skill<String, String>("answer-from-docs", "Answers from project docs") {
    knowledge(
        "project-docs", "Product specs and ADRs",
        ragRetriever(pgvectorStore, openAiEmbedder) {
            topK = 8
            minScore = 0.55f
            filter { it["team"] == "platform" }
        },
    )
    tools()
}
```

What the model sees: a knowledge tool named `project-docs` taking a single `query` argument. Unlike static `knowledge(key) { content }` entries (inlined into the prompt), retriever entries are **on-demand only** — the prompt carries a one-line pointer, and each model call embeds the query, hits the store, and returns matches rendered with score / chunk id / source / content-hash lines. The call goes through the normal tool path, so it lands in the JSONL audit trail and respects the per-skill allowlist; the manifest lists the entry under the skill's `knowledge` block like any other.

On the session path the retriever runs natively suspend; on the blocking path it bridges like any other blocking tool.

## Adapters

| Module | Wraps | Embedding | Notes |
|---|---|---|---|
| `:agents-kt-rag-langchain4j` | `dev.langchain4j.store.embedding.EmbeddingStore<TextSegment>` (PgVector, Elasticsearch, Chroma, …) | You supply an `Embedder` (`RagQuery.embedding` required) | Locked to LangChain4j 1.16.x |
| `:agents-kt-rag-spring-ai` | `org.springframework.ai.vectorstore.VectorStore` (PgVector, Redis, Milvus, `SimpleVectorStore`, …) | The store embeds internally — **no `Embedder` needed**; `Embedded.embedding` is ignored on upsert | Locked to Spring AI 1.1.x (2.0 is RC) |

Both adapters translate the store's metadata into `Provenance` (`chunkId`, `metadata["source"]` → `sourceUri`, SHA-256 of the chunk text → `hash`) and apply `Filter`s client-side. Their delegates' blocking calls run on the caller's thread — dispatch around the retriever if your store does network I/O.

## What this deliberately does not do

- **No embedding model shipped** — bring an `Embedder` (or use Spring AI's internal embedding path).
- **No vector-DB lifecycle** — creation, sharding, GC are the deployer's.
- **No re-ranking / query expansion / hybrid search** — follow-up tickets; the SPI stays minimal.
- **No streaming retrieval** — results return as one rendered block per tool call.
