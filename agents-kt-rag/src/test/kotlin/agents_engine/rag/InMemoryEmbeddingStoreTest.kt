package agents_engine.rag

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// #3863 — reference store: cosine ranking, upsert-replace, metadata
// filtering, provenance fields.

class InMemoryEmbeddingStoreTest {

    private fun embedded(id: String, text: String, vector: FloatArray, metadata: Map<String, String> = emptyMap()) =
        Embedded(value = text, embedding = Embedding(vector), id = id, metadata = metadata)

    @Test
    fun `query ranks by cosine similarity and respects topK`() = runTest {
        val store = InMemoryEmbeddingStore<String>()
        store.upsert(
            listOf(
                embedded("a", "close", floatArrayOf(1f, 0f)),
                embedded("b", "closer", floatArrayOf(0.9f, 0.1f)),
                embedded("c", "far", floatArrayOf(0f, 1f)),
            ),
        )

        val matches = store.query(RagQuery("q", Embedding(floatArrayOf(1f, 0f))), topK = 2)

        assertEquals(listOf("close", "closer"), matches.map { it.value }, "cosine order; got: $matches")
        assertTrue(matches[0].score > matches[1].score)
    }

    @Test
    fun `upsert replaces by id`() = runTest {
        val store = InMemoryEmbeddingStore<String>()
        store.upsert(listOf(embedded("a", "v1", floatArrayOf(1f, 0f))))
        store.upsert(listOf(embedded("a", "v2", floatArrayOf(1f, 0f))))

        val matches = store.query(RagQuery("q", Embedding(floatArrayOf(1f, 0f))), topK = 10)
        assertEquals(listOf("v2"), matches.map { it.value })
    }

    @Test
    fun `filter excludes non-matching metadata and provenance carries source and hash`() = runTest {
        val store = InMemoryEmbeddingStore<String>()
        store.upsert(
            listOf(
                embedded("a", "platform doc", floatArrayOf(1f, 0f), mapOf("team" to "platform", "source" to "s3://docs/a")),
                embedded("b", "other doc", floatArrayOf(1f, 0f), mapOf("team" to "growth")),
            ),
        )

        val matches = store.query(
            RagQuery("q", Embedding(floatArrayOf(1f, 0f))),
            topK = 10,
            filter = { metadata -> metadata["team"] == "platform" },
        )

        assertEquals(1, matches.size, "filter must drop the growth doc; got: $matches")
        val provenance = matches.single().provenance
        assertEquals("a", provenance.chunkId)
        assertEquals("s3://docs/a", provenance.sourceUri)
        assertEquals(64, provenance.hash?.length, "sha-256 hex hash expected")
    }

    @Test
    fun `query without an embedding fails loud`() = runTest {
        val store = InMemoryEmbeddingStore<String>()
        assertFailsWith<IllegalArgumentException> {
            store.query(RagQuery("text only"), topK = 1)
        }
    }
}
