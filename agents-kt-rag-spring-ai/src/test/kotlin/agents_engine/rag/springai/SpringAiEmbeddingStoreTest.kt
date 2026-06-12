package agents_engine.rag.springai

import agents_engine.rag.Embedded
import agents_engine.rag.Embedding
import agents_engine.rag.RagQuery
import kotlinx.coroutines.test.runTest
import org.springframework.ai.document.Document
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.embedding.EmbeddingRequest
import org.springframework.ai.embedding.EmbeddingResponse
import org.springframework.ai.vectorstore.SimpleVectorStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// #3863 — adapter contract against Spring AI's own SimpleVectorStore with a
// deterministic fake EmbeddingModel (Spring AI stores embed internally).

class SpringAiEmbeddingStoreTest {

    private class FakeEmbeddingModel : EmbeddingModel {
        // [vowels, consonants, length] — deterministic and rank-capable.
        private fun vectorFor(text: String): FloatArray {
            val vowels = text.count { it.lowercaseChar() in "aeiou" }.toFloat()
            val consonants = text.count { it.isLetter() && it.lowercaseChar() !in "aeiou" }.toFloat()
            return floatArrayOf(vowels + 1f, consonants + 1f, text.length.toFloat() + 1f)
        }

        override fun call(request: EmbeddingRequest): EmbeddingResponse =
            EmbeddingResponse(
                request.instructions.mapIndexed { index, text ->
                    org.springframework.ai.embedding.Embedding(vectorFor(text), index)
                },
            )

        override fun embed(document: Document): FloatArray = vectorFor(document.text.orEmpty())
    }

    @Test
    fun `upsert then text query round-trips values and provenance without an embedder`() = runTest {
        val store = SpringAiEmbeddingStore(SimpleVectorStore.builder(FakeEmbeddingModel()).build())
        store.upsert(
            listOf(
                Embedded("release runbook steps", Embedding(floatArrayOf(0f)), "doc-1", mapOf("source" to "docs/runbook")),
                Embedded("zzzzzz", Embedding(floatArrayOf(0f)), "doc-2"),
            ),
        )

        // Note: RagQuery.embedding is absent — Spring AI embeds the text internally.
        val matches = store.query(RagQuery("release runbook steps"), topK = 1)

        assertEquals("release runbook steps", matches.single().value)
        val provenance = matches.single().provenance
        assertEquals("doc-1", provenance.chunkId)
        assertEquals("docs/runbook", provenance.sourceUri)
        assertEquals(64, provenance.hash?.length)
    }

    @Test
    fun `client-side filter applies to document metadata`() = runTest {
        val store = SpringAiEmbeddingStore(SimpleVectorStore.builder(FakeEmbeddingModel()).build())
        store.upsert(
            listOf(
                Embedded("platform doc", Embedding(floatArrayOf(0f)), "p", mapOf("team" to "platform")),
                Embedded("platform dog", Embedding(floatArrayOf(0f)), "g", mapOf("team" to "growth")),
            ),
        )

        val matches = store.query(
            RagQuery("platform doc"),
            topK = 10,
            filter = { it["team"] == "growth" },
        )

        assertEquals(listOf("platform dog"), matches.map { it.value })
        assertTrue(matches.single().score >= 0f)
    }
}
