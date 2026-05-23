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
 * #1742 — live integration test for ClaudeClient.chatStream against the
 * real Anthropic API. Requires an API key at `.secrets/anthropic-key`
 * or in `ANTHROPIC_API_KEY`. Tagged `live-llm` — runs via
 * `./gradlew integrationTest`. Skips cleanly when no key is available.
 */
class ClaudeClientChatStreamLiveTest {

    private val apiKey: String? = loadKey()
    private val claudeModel: String = System.getenv("CLAUDE_TEST_MODEL") ?: "claude-haiku-4-5-20251001"

    @Tag("live-llm")
    @Test
    fun `native chatStream against Anthropic emits multiple TextDelta chunks incrementally with token usage`() = runBlocking {
        assumeTrue(apiKey != null, "skipping: no Anthropic key at .secrets/anthropic-key or ANTHROPIC_API_KEY")

        val client = ClaudeClient(apiKey = apiKey!!, model = claudeModel, temperature = 0.0)

        val startNs = System.nanoTime()
        val arrivals = mutableListOf<Pair<Long, LlmChunk>>()
        client.chatStream(
            listOf(
                LlmMessage(
                    role = "user",
                    // #2380 — long enough response to force the SSE path to
                    // emit many small text-delta blocks. The previous "1..10"
                    // prompt was short enough that Haiku occasionally bundled
                    // the whole reply into two same-millisecond chunks,
                    // which is valid streaming behavior but defeats the
                    // chunk-count + timing assertion below.
                    content = "Count from 1 to 50 separated by spaces. Output ONLY the numbers, nothing else.",
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
        // The load-bearing assertion is "more than one chunk arrived"
        // (above) — that's the real proof of streaming. This secondary
        // assertion catches the regression where the wire-level SSE
        // implementation has accidentally re-bundled into ~1-2 mega
        // chunks. The prompt above (1..50) reliably produces 10+ deltas
        // on Haiku, so we keep the chunk-count side at >=5; the timing
        // side is the more lenient backup for adapters that emit many
        // chunks within a single millisecond. Either alone disproves
        // "bundled at end".
        assertTrue(
            gapMs >= 10 || textDeltas.size >= 5,
            "expected either >=10ms gap OR >=5 chunks; first=${firstMs}ms last=${lastMs}ms gap=${gapMs}ms chunks=${textDeltas.size}",
        )

        val usage = endChunk.tokenUsage
        assertNotNull(usage, "End chunk must carry TokenUsage")
        assertTrue(usage.completionTokens > 0)

        val assembled = textDeltas.joinToString("") { (it.second as LlmChunk.TextDelta).text }
        listOf("1", "25", "50").forEach { d ->
            assertTrue(d in assembled, "assembled output should contain '$d'; got: \"$assembled\"")
        }

        println(
            "ClaudeClientChatStreamLiveTest: model=$claudeModel chunks=${textDeltas.size} " +
                "firstMs=$firstMs lastMs=$lastMs gapMs=$gapMs assembled=\"$assembled\""
        )
    }

    private fun loadKey(): String? {
        val envKey = System.getenv("ANTHROPIC_API_KEY")
        if (!envKey.isNullOrBlank()) return envKey
        val file = File(".secrets/anthropic-key")
        return if (file.exists()) file.readText().trim().ifBlank { null } else null
    }
}
