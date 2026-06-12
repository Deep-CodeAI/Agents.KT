package agents_engine.rag.langchain4j

import agents_engine.rag.Embedded
import agents_engine.rag.Embedding
import agents_engine.rag.RagQuery
import dev.langchain4j.data.segment.TextSegment
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// #3863 — adapter contract against LangChain4j's own InMemoryEmbeddingStore.

class LangChain4jEmbeddingStoreTest {

    @Test
    fun `upsert then query round-trips values, scores, and provenance`() = runTest {
        val store = LangChain4jEmbeddingStore(InMemoryEmbeddingStore<TextSegment>())
        store.upsert(
            listOf(
                Embedded("alpha doc", Embedding(floatArrayOf(1f, 0f)), "id-alpha", mapOf("source" to "uri://alpha")),
                Embedded("beta doc", Embedding(floatArrayOf(0f, 1f)), "id-beta"),
            ),
        )

        val matches = store.query(RagQuery("q", Embedding(floatArrayOf(1f, 0f))), topK = 2)

        assertEquals("alpha doc", matches.first().value, "closest vector wins; got: $matches")
        assertTrue(matches.first().score >= matches.last().score)
        val provenance = matches.first().provenance
        assertEquals("uri://alpha", provenance.sourceUri)
        assertEquals(64, provenance.hash?.length, "sha-256 hex of the segment text")
    }

    @Test
    fun `client-side filter applies to segment metadata`() = runTest {
        val store = LangChain4jEmbeddingStore(InMemoryEmbeddingStore<TextSegment>())
        store.upsert(
            listOf(
                Embedded("platform", Embedding(floatArrayOf(1f, 0f)), "p", mapOf("team" to "platform")),
                Embedded("growth", Embedding(floatArrayOf(1f, 0f)), "g", mapOf("team" to "growth")),
            ),
        )

        val matches = store.query(
            RagQuery("q", Embedding(floatArrayOf(1f, 0f))),
            topK = 10,
            filter = { it["team"] == "growth" },
        )

        assertEquals(listOf("growth"), matches.map { it.value })
    }
}
