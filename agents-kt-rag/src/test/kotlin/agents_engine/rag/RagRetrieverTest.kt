package agents_engine.rag

import agents_engine.core.skill
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// #3863 — ragRetriever bridges the SPI into the core knowledge seam:
// minScore drops weak matches, rendering carries provenance, and the
// skill DSL surfaces the entry as a query-aware knowledge tool.

class RagRetrieverTest {

    // Deterministic 2-dim "embedder": [vowels, consonants] — enough to rank.
    private val embedder = Embedder { text ->
        val vowels = text.count { it.lowercaseChar() in "aeiou" }.toFloat()
        val consonants = text.count { it.isLetter() && it.lowercaseChar() !in "aeiou" }.toFloat()
        Embedding(floatArrayOf(vowels + 1f, consonants + 1f))
    }

    private suspend fun seededStore(): InMemoryEmbeddingStore<String> {
        val store = InMemoryEmbeddingStore<String>()
        store.upsert(
            listOf(
                Embedded("the deploy runbook lives in docs/RELEASE_RUNBOOK.md", embedder.embed("deploy runbook"), "chunk-1", mapOf("source" to "docs/")),
                Embedded("budget caps: maxTurns, maxToolCalls, perToolTimeout", embedder.embed("budget caps"), "chunk-2"),
            ),
        )
        return store
    }

    @Test
    fun `retriever renders matches with provenance lines`() = runTest {
        val retriever = ragRetriever(seededStore(), embedder) { topK = 1 }

        val rendered = retriever.retrieve("deploy runbook")

        assertTrue(rendered.contains("chunk="), "rendered output must carry chunk ids; got: $rendered")
        assertTrue(rendered.contains("score="), "rendered output must carry scores; got: $rendered")
        assertTrue(rendered.contains("hash="), "rendered output must carry content hashes; got: $rendered")
    }

    @Test
    fun `minScore drops weak matches and empty results render a no-match line`() = runTest {
        val retriever = ragRetriever(seededStore(), embedder) {
            topK = 5
            minScore = 1.1f // above max cosine — nothing survives
        }

        val rendered = retriever.retrieve("anything")
        assertTrue(rendered.startsWith("No knowledge matches"), "got: $rendered")
    }

    @Test
    fun `skill DSL surfaces a rag entry as a query-aware knowledge tool, not inlined content`() = runTest {
        val store = seededStore()
        val s = skill<String, String>("answer", "Answers from docs") {
            knowledge("project-docs", "Project documentation", ragRetriever(store, embedder))
            implementedBy { it }
        }

        val tools = s.knowledgeTools()
        assertEquals(listOf("project-docs"), tools.map { it.name })
        val tool = tools.single()
        assertTrue(tool.retriever != null, "rag knowledge must surface as a query-aware tool")
        val retrieved = tool.retriever!!.retrieve("budget caps")
        assertTrue(retrieved.contains("budget caps"), "retrieval must reach the store; got: $retrieved")

        val context = s.toLlmContext()
        assertTrue(
            "on-demand" in context && "runbook" !in context,
            "rag content must NOT be inlined into the prompt; got: $context",
        )
    }
}
