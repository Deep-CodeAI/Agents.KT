package agents_engine.model

import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * #1743 — live integration test for OpenAiClient.chatStream against the
 * real OpenAI API. Requires an API key at `.secrets/openai-key` or in
 * `OPENAI_API_KEY`. Tagged `live-llm` — runs via `./gradlew integrationTest`.
 */
class OpenAiClientChatStreamLiveTest {

    private val apiKey: String? = loadKey()
    private val openAiModel: String = System.getenv("OPENAI_TEST_MODEL") ?: "gpt-4o-mini"

    @Tag("live-llm")
    @Test
    fun `native chatStream against OpenAI emits multiple TextDelta chunks incrementally with token usage`() = runBlocking {
        assumeTrue(apiKey != null, "skipping: no OpenAI key at .secrets/openai-key or OPENAI_API_KEY")

        val client = OpenAiClient(apiKey = apiKey!!, model = openAiModel, temperature = 0.0)

        val startNs = System.nanoTime()
        val arrivals = mutableListOf<Pair<Long, LlmChunk>>()
        client.chatStream(
            listOf(
                LlmMessage(
                    role = "user",
                    content = "Count from 1 to 10 separated by spaces. Output ONLY the numbers, nothing else.",
                ),
            ),
        ).collect { chunk ->
            arrivals += ((System.nanoTime() - startNs) / 1_000_000) to chunk
        }

        val textDeltas = arrivals.filter { it.second is LlmChunk.TextDelta }
        val endChunk = arrivals.last().second as? LlmChunk.End
            ?: error("last chunk must be End; got: ${arrivals.last().second}")

        assertTrue(
            textDeltas.size > 1,
            "expected multiple TextDelta chunks (proves wire-level SSE streaming); got ${textDeltas.size}",
        )

        val firstMs = textDeltas.first().first
        val lastMs = textDeltas.last().first
        val gapMs = lastMs - firstMs
        assertTrue(
            gapMs >= 20,
            "expected at least 20ms between first and last TextDelta; first=${firstMs}ms last=${lastMs}ms gap=${gapMs}ms",
        )

        assertNotNull(endChunk.tokenUsage, "End chunk must carry TokenUsage (stream_options.include_usage)")
        assertTrue(endChunk.tokenUsage!!.completionTokens > 0)

        val assembled = textDeltas.joinToString("") { (it.second as LlmChunk.TextDelta).text }
        listOf("1", "2", "3").forEach { d ->
            assertTrue(d in assembled, "assembled output should contain '$d'; got: \"$assembled\"")
        }

        println(
            "OpenAiClientChatStreamLiveTest: model=$openAiModel chunks=${textDeltas.size} " +
                "firstMs=$firstMs lastMs=$lastMs gapMs=$gapMs assembled=\"$assembled\""
        )
    }

    private fun loadKey(): String? {
        val envKey = System.getenv("OPENAI_API_KEY")
        if (!envKey.isNullOrBlank()) return envKey
        val file = File(".secrets/openai-key")
        return if (file.exists()) file.readText().trim().ifBlank { null } else null
    }
}
