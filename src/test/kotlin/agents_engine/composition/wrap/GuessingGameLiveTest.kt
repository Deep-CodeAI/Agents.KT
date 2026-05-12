package agents_engine.composition.wrap

import agents_engine.composition.loop.loop
import agents_engine.core.agent
import agents_engine.core.skill
import agents_engine.model.LlmResponse
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Live variant of the [GuessingGameTest] for #1699 — real LLM as the guesser.
 *
 * The unit test uses a deterministic Kotlin "guesser" that does perfect
 * binary search via the prompt. This variant swaps that out for a real
 * Claude haiku model so we can observe an actual LLM converging on a
 * secret integer over multiple rounds, with the system prompt updated
 * each round via `wrap`.
 *
 * Gating: `@Tag("live-llm")` — excluded from the default test task;
 * runs via `./gradlew integrationTest`. Skips cleanly when no Anthropic
 * key is reachable.
 *
 * Real LLMs are noisier than binary search; we allow up to 14 rounds and
 * tolerate wording variation (numbers may arrive as `"42"`, `"42."`,
 * `"My guess is 42"`, etc.).
 */
class GuessingGameLiveTest {

    private val anthropicKey: String? = loadKey("anthropic-key", "ANTHROPIC_API_KEY")
    private val claudeModel: String = System.getenv("CLAUDE_TEST_MODEL") ?: "claude-haiku-4-5-20251001"

    @Tag("live-llm")
    @Test
    fun `wrap inside Loop — Claude guesser converges on secret 42 via oracle prompt updates`() {
        assumeTrue(anthropicKey != null, "skipping: no Anthropic key at .secrets/anthropic-key or ANTHROPIC_API_KEY")
        val key = anthropicKey!!

        val secret = 42
        var low = 1
        var high = 100
        val guessLog = mutableListOf<Int>()

        // Oracle (Kotlin): holds secret + window state. Maps the latest
        // guess to a fresh system prompt for the guesser.
        val oracle = agent<String, String>("oracle-live") {
            skills {
                skill<String, String>("teach", "Narrow window, emit new prompt") {
                    implementedBy { latestGuess ->
                        extractInt(latestGuess)?.let { g ->
                            guessLog += g
                            if (g < secret) low = maxOf(low, g + 1)
                            else if (g > secret) high = minOf(high, g - 1)
                        }
                        // Plain-language prompt — robust against tooling drift.
                        buildString {
                            append("You are playing higher-or-lower. ")
                            append("The secret integer is between $low and $high inclusive. ")
                            append("Pick your next guess as the integer midpoint of that window. ")
                            append("Reply with ONLY the integer, no commentary.")
                        }
                    }
                }
            }
        }

        // Guesser (real Claude): plain text agent, no tools, prompt-driven.
        val guesser = agent<String, String>("guesser-live") {
            prompt("baked-in default — the oracle will replace this every round")
            model {
                claude(claudeModel)
                apiKey = key
                temperature = 0.0
                maxTokens = 32  // single integer per turn — keep the bill small
            }
            skills {
                skill<String, String>("guess", "Reply with the next guess as an integer") {
                    tools()  // text-only
                }
            }
        }

        val game = (oracle wrap guesser).loop(maxIterations = 14) { reply ->
            val n = extractInt(reply)
            // Loop's feedback becomes the next input to BOTH agents (the
            // pipeline runs them with the same input). Always pass back a
            // non-empty string — Claude rejects empty user messages.
            if (n == secret) null else (n?.toString() ?: "guess")
        }

        // Round-1 input flows to both agents. Oracle ignores non-integer
        // inputs (window stays at [1, 100]); Claude's user message must be
        // non-empty so kick off with a plain "start" token.
        val finalReply = game("start")

        // 1. The game terminated because the model emitted the secret.
        assertEquals(
            secret,
            extractInt(finalReply),
            "expected final guess to extract to $secret; got: $finalReply (history: $guessLog)",
        )

        // 2. Convergence in reasonable rounds. log2(100) ≈ 6.6; allow slack
        //    for LLM noise (off-by-one midpoints, occasional re-guesses).
        assertTrue(
            guessLog.size <= 14,
            "expected ≤ 14 rounds to converge; took ${guessLog.size}: $guessLog",
        )

        // 3. Window collapsed onto the secret.
        assertTrue(low <= secret && secret <= high)
    }

    /**
     * Extract the first integer 1..100 from a free-form LLM reply.
     * Real LLMs sometimes wrap the number in prose; this is robust to that.
     */
    private fun extractInt(s: String): Int? =
        Regex("""\b(\d{1,3})\b""").findAll(s)
            .map { it.groupValues[1].toInt() }
            .firstOrNull { it in 1..100 }

    private fun loadKey(fileName: String, envVar: String): String? {
        val path = Paths.get(".secrets", fileName)
        if (Files.isReadable(path)) {
            val raw = Files.readString(path).trim()
            if (raw.isNotEmpty()) return raw
        }
        return System.getenv(envVar)?.takeIf { it.isNotBlank() }
    }

    // Suppress unused — `LlmResponse` import is here for symmetry with other
    // live tests that may grow assertions on TokenUsage.
    @Suppress("unused")
    private val _llmResponse = LlmResponse::class.simpleName
}
