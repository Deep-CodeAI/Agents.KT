package agents_engine.model

import agents_engine.core.agent
import agents_engine.core.skill
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * #3676 — full agentic-loop live test: an agent reasoning on its OWN model
 * (Claude) calls the `perplexitySearch` tool mid-reasoning to ground its
 * answer. Mirrors `KimiClientIntegrationTest`'s full-loop test, but here the
 * model and the search provider are different (the composable shape the tool
 * is for). Tagged `live-cloud-api`; needs BOTH an Anthropic key and a
 * Perplexity key, skips cleanly otherwise.
 */
class PerplexitySearchAgenticLiveTest {

    private val perplexityKey: String? = loadKey("perplexity-key", "PERPLEXITY_API_KEY")
    private val anthropicKey: String? = loadKey("anthropic-key", "ANTHROPIC_API_KEY")
    private val claudeModel: String = System.getenv("CLAUDE_TEST_MODEL") ?: "claude-haiku-4-5-20251001"

    @Tag("live-cloud-api")
    @Test
    fun `claude agent invokes perplexitySearch and grounds its answer`() {
        assumeTrue(perplexityKey != null, "skipping: no Perplexity key")
        assumeTrue(anthropicKey != null, "skipping: no Anthropic key")

        // Wrap the real backend so we can assert the tool was actually invoked.
        val realBackend = HttpPerplexitySearchBackend(perplexityKey!!)
        val queriesSeen = mutableListOf<String>()
        val recordingBackend = PerplexitySearchBackend { q, o ->
            queriesSeen += q
            realBackend.search(q, o)
        }
        val searchTool = perplexitySearchTool(perplexityKey, backend = recordingBackend)

        val researcher = agent<String, String>("pplx-researcher") {
            prompt(
                "You answer questions using live web search. You MUST call the perplexity_search " +
                    "tool before answering — never answer factual questions from memory. After the " +
                    "tool returns, reply with a concise final answer.",
            )
            model {
                claude(claudeModel)
                apiKey = anthropicKey
                temperature = 0.0
                maxTokens = 512
            }
            tools { +searchTool }
            skills {
                @Suppress("DEPRECATION") // string form is the documented path for externally-built ToolDefs
                skill<String, String>("research", "Research a question with web search") {
                    tools("perplexity_search")
                }
            }
        }

        val answer = runBlocking { researcher.invokeSuspend("What is the capital of France?") }

        assertTrue(queriesSeen.isNotEmpty(), "the agent must call perplexity_search; final answer was '$answer'")
        assertTrue(answer.contains("Paris"), "expected a grounded final answer mentioning Paris, got '$answer'")
    }

    private fun loadKey(fileName: String, envVar: String): String? {
        val path = Paths.get(".secrets", fileName)
        if (Files.isReadable(path)) {
            val raw = Files.readString(path).trim()
            if (raw.isNotEmpty()) return raw
        }
        return System.getenv(envVar)?.takeIf { it.isNotBlank() }
    }
}
