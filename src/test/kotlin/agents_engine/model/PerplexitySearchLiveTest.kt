package agents_engine.model

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * #3676 — live smoke test for the `perplexitySearch` tool against the real
 * Perplexity API. Tagged `live-cloud-api` (parity with DeepSeek/OpenAI/Anthropic)
 * — runs in the default `:test` task when a key is present and skips cleanly
 * otherwise. Verified end-to-end with a live key.
 */
class PerplexitySearchLiveTest {

    private val apiKey: String? = loadApiKey()

    @Tag("live-cloud-api")
    @Test
    fun `grounded search returns an answer and at least one source`() {
        assumeTrue(apiKey != null, "skipping: no Perplexity key at .secrets/perplexity-key or PERPLEXITY_API_KEY")
        val tool = perplexitySearchTool(apiKey = apiKey!!)

        val result = tool.executor(mapOf("query" to "What is the capital of France?"))

        assertTrue(result is PerplexitySearchResult, "expected a parsed result, got: $result")
        assertTrue(result.answer.isNotBlank(), "expected a non-blank grounded answer")
        assertTrue(result.answer.contains("Paris"), "expected grounded answer mentioning Paris, got '${result.answer}'")
        assertTrue(result.sources.isNotEmpty(), "expected at least one citation/source")
    }

    private fun loadApiKey(): String? {
        val path = Paths.get(".secrets", "perplexity-key")
        if (Files.isReadable(path)) {
            val raw = Files.readString(path).trim()
            if (raw.isNotEmpty()) return raw
        }
        return System.getenv("PERPLEXITY_API_KEY")?.takeIf { it.isNotBlank() }
    }
}
