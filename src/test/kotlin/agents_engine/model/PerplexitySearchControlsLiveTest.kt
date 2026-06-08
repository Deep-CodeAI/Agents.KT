package agents_engine.model

import agents_engine.generation.Generable
import agents_engine.generation.Guide
import agents_engine.generation.LenientJsonParser
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * #3677 — live exercise of the `perplexitySearch` search controls against the
 * real Perplexity API (search_mode, domain filter, native structured output).
 * Complements the offline serialization tests (`PerplexitySearchControlsTest`)
 * by proving the API actually accepts and honors the controls. Tagged
 * `live-cloud-api`; key-gated, skips cleanly without a key.
 */
class PerplexitySearchControlsLiveTest {

    private val apiKey: String? = loadApiKey()

    @Tag("live-cloud-api")
    @Test
    fun `academic mode returns a grounded answer with sources`() {
        assumeTrue(apiKey != null, "skipping: no Perplexity key")
        val tool = perplexitySearchTool(
            apiKey!!,
            perplexitySearchOptions {
                mode = SearchMode.ACADEMIC
                recency = SearchRecency.YEAR
            },
        )

        val result = tool.executor(mapOf("query" to "What is CRISPR gene editing?"))

        assertTrue(result is PerplexitySearchResult, "expected a parsed result, got: $result")
        assertTrue(result.answer.isNotBlank(), "expected a non-blank grounded answer")
        assertTrue(result.sources.isNotEmpty(), "academic mode should still return sources")
    }

    @Tag("live-cloud-api")
    @Test
    fun `domain filter confines sources to the allowed domain`() {
        assumeTrue(apiKey != null, "skipping: no Perplexity key")
        val tool = perplexitySearchTool(
            apiKey!!,
            perplexitySearchOptions { allowDomains("wikipedia.org") },
        )

        val result = tool.executor(mapOf("query" to "What is the capital of France?"))

        assertTrue(result is PerplexitySearchResult, "expected a parsed result, got: $result")
        assertTrue(result.answer.contains("Paris"), "expected grounded answer mentioning Paris, got '${result.answer}'")
        assertTrue(result.sources.isNotEmpty(), "expected sources")
        val urls = result.sources.map { it.url }
        assertTrue(
            urls.any { it.contains("wikipedia.org") },
            "search_domain_filter=[wikipedia.org] should surface a wikipedia source, got $urls",
        )
    }

    @Tag("live-cloud-api")
    @Test
    fun `structured output returns schema-shaped JSON in the answer`() {
        assumeTrue(apiKey != null, "skipping: no Perplexity key")
        val tool = perplexitySearchTool(
            apiKey!!,
            perplexitySearchOptions {
                model = "sonar-pro" // structured outputs are most reliable on the pro tier
                structuredOutput(CapitalAnswer::class)
            },
        )

        val result = tool.executor(mapOf("query" to "What is the capital of France? Give the country and its capital."))

        assertTrue(result is PerplexitySearchResult, "expected a parsed result, got: $result")
        val parsed = LenientJsonParser.parse(result.answer) as? Map<*, *>
        assertTrue(parsed != null, "answer should be schema-shaped JSON, got '${result.answer}'")
        assertTrue(
            (parsed!!["capital"] as? String)?.contains("Paris") == true,
            "expected capital=Paris in the structured answer, got $parsed",
        )
    }

    private fun loadApiKey(): String? {
        val path = Paths.get(".secrets", "perplexity-key")
        if (Files.isReadable(path)) {
            val raw = Files.readString(path).trim()
            if (raw.isNotEmpty()) return raw
        }
        return System.getenv("PERPLEXITY_API_KEY")?.takeIf { it.isNotBlank() }
    }

    @Generable("A country and its capital city")
    data class CapitalAnswer(
        @Guide("the country name") val country: String,
        @Guide("the capital city of that country") val capital: String,
    )
}
