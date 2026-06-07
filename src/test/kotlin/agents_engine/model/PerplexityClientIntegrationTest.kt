package agents_engine.model

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * #3675 — live Perplexity (Sonar) Chat Completions smoke test. Mirrors
 * `KimiClientIntegrationTest`. Tagged `live-llm` (excluded from the default
 * `:test` task) and key-gated via JUnit `Assumptions`, so it runs only when a
 * Perplexity key is present and skips cleanly otherwise.
 *
 * Key loading:
 * - reads `<repo-root>/.secrets/perplexity-key` (gitignored)
 * - falls back to `PERPLEXITY_API_KEY`
 * - skips via `assumeTrue` if neither is present
 *
 * Note on tag: gated `live-llm` (excluded from default `:test`) rather than
 * the DeepSeek-style `live-cloud-api`. Reason: as of the initial branch
 * commit, the local `.secrets/perplexity-key` returns `Invalid API key
 * provided` from `api.perplexity.ai` (confirmed via this suite — code paths
 * are independent and validated). Flip to `live-cloud-api` once the key
 * validates so this suite gains parity with DeepSeek's default-run coverage.
 */
class PerplexityClientIntegrationTest {

    private val apiKey: String? = loadApiKey()
    private val model: String = System.getenv("PERPLEXITY_TEST_MODEL") ?: "sonar"

    @Tag("live-llm")
    @Test
    fun `returns grounded text response for simple prompt`() {
        assumeTrue(apiKey != null, "skipping: no Perplexity key at .secrets/perplexity-key or PERPLEXITY_API_KEY")
        val client = PerplexityClient(apiKey = apiKey!!, model = model, temperature = 0.0, maxTokens = 64)

        val response = client.chat(listOf(
            LlmMessage("user", "In one short sentence, what is the capital of France?"),
        ))

        val text = assertIs<LlmResponse.Text>(response)
        assertTrue(text.content.isNotBlank(), "expected non-blank text, got '${text.content}'")
        assertTrue(text.content.contains("Paris"), "expected grounded answer mentioning Paris, got '${text.content}'")
        assertTrue((text.tokenUsage?.total ?: 0) > 0, "expected positive token usage, got ${text.tokenUsage}")
        kotlin.test.assertEquals("perplexity", text.tokenUsage?.provider)
    }

    @Tag("live-llm")
    @Test
    fun `streaming response emits text deltas and Perplexity usage`() = runBlocking {
        assumeTrue(apiKey != null, "skipping: no Perplexity key at .secrets/perplexity-key or PERPLEXITY_API_KEY")
        val client = PerplexityClient(apiKey = apiKey!!, model = model, temperature = 0.0, maxTokens = 64)

        val chunks = client.chatStream(listOf(
            LlmMessage("user", "Name the largest planet in our solar system. Answer with just the name."),
        )).toList()

        assertTrue(chunks.isNotEmpty(), "expected streaming chunks")
        val end = assertIs<LlmChunk.End>(chunks.last())
        val text = chunks.dropLast(1)
            .filterIsInstance<LlmChunk.TextDelta>()
            .joinToString("") { it.text }
        assertTrue("Jupiter" in text, "expected grounded stream answer, got '$text'")
        kotlin.test.assertEquals("perplexity", end.tokenUsage?.provider)
        assertTrue((end.tokenUsage?.total ?: 0) > 0, "expected Perplexity stream usage, got ${end.tokenUsage}")
    }

    private fun loadApiKey(): String? {
        val path: Path = Paths.get(".secrets", "perplexity-key")
        if (Files.isReadable(path)) {
            val raw = Files.readString(path).trim()
            if (raw.isNotEmpty()) return raw
        }
        return System.getenv("PERPLEXITY_API_KEY")?.takeIf { it.isNotBlank() }
    }
}
