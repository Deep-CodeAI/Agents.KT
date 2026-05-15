package agents_engine.model

import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * #1741 — live integration test for the native Ollama `chatStream`
 * override. Confirms real wire-level streaming: multiple `TextDelta`
 * chunks arrive at different times during the response, not all
 * batched at the end. Plus token usage on the final `End` chunk.
 *
 * Tagged `live-llm` — excluded from `./gradlew test`. Runs via
 * `./gradlew integrationTest`. Skips cleanly when Ollama isn't running.
 */
class OllamaClientChatStreamLiveTest {

    private val ollamaModel: String = System.getenv("AGENTSKT_TEST_OLLAMA_MODEL") ?: "gpt-oss:120b-cloud"

    @Tag("live-llm")
    @Test
    fun `native chatStream emits multiple TextDelta chunks incrementally with token usage on End`() = runBlocking {
        assumeTrue(isOllamaReachable(), "skipping: no Ollama at localhost:11434")

        val client = OllamaClient(model = ollamaModel, temperature = 0.0)

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
        val endChunk = arrivals.last().second
        val end = endChunk as? LlmChunk.End
            ?: error("last chunk must be End; got: $endChunk")

        // Real wire-level streaming → more than one TextDelta chunk arrives.
        assertTrue(
            textDeltas.size > 1,
            "expected multiple TextDelta chunks (proves wire-level streaming); got ${textDeltas.size}",
        )

        // Incrementality: first and last TextDelta arrival times differ
        // measurably. 50ms is generous slack; an actual streamed response
        // typically sees hundreds of ms across many chunks.
        val firstMs = textDeltas.first().first
        val lastMs = textDeltas.last().first
        val gapMs = lastMs - firstMs
        assertTrue(
            gapMs >= 50,
            "expected at least 50ms between first and last TextDelta (proves incremental); " +
                "got first=${firstMs}ms last=${lastMs}ms gap=${gapMs}ms",
        )

        // End must report token usage — Ollama always sends prompt + eval counts.
        assertNotNull(end.tokenUsage, "End chunk must carry TokenUsage for Ollama responses")
        assertTrue(end.tokenUsage!!.completionTokens > 0)

        // Assembled output should contain the digits the prompt asked for.
        val assembled = textDeltas.joinToString("") { (it.second as LlmChunk.TextDelta).text }
        listOf("1", "2", "3").forEach { d ->
            assertTrue(d in assembled, "assembled output should contain '$d'; got: \"$assembled\"")
        }

        println(
            "OllamaClientChatStreamLiveTest: model=$ollamaModel chunks=${textDeltas.size} " +
                "firstMs=$firstMs lastMs=$lastMs gapMs=$gapMs assembled=\"$assembled\""
        )
    }

    private fun isOllamaReachable(): Boolean = try {
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(500)).build()
        val request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:11434/api/tags"))
            .timeout(Duration.ofMillis(1500))
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.discarding())
        response.statusCode() in 200..299
    } catch (_: Throwable) {
        false
    }
}
