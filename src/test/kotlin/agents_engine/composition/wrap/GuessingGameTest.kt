package agents_engine.composition.wrap

import agents_engine.composition.loop.loop
import agents_engine.core.agent
import agents_engine.core.skill
import agents_engine.model.LlmResponse
import agents_engine.model.ModelClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guessing-game demo for the `wrap` operator (#1699).
 *
 * Iterative use of `wrap` inside a `Loop`. Two agents play a
 * higher-or-lower game:
 *
 * - **Oracle** holds a secret integer in `[1..100]` in closure state.
 *   On each round it receives the latest guess as input, narrows its
 *   running `[low, high]` window, and emits the *next* system prompt
 *   for the guesser encoding the new bounds.
 * - **Guesser** reads its own runtime system prompt (the one the oracle
 *   just emitted) to learn the current window, then emits the midpoint
 *   as its next guess. Binary search → converges in ≤ 7 rounds.
 *
 * The point of the test is the composition shape, not the binary search:
 *
 * ```kotlin
 * val game = (oracle wrap guesser).loop(maxIterations = 12) { guess ->
 *     if (guess.trim() == secret.toString()) null else guess
 * }
 * ```
 *
 * - `wrap`'s `markPlaced` fires once at construction; the Loop reuses
 *   the resulting Pipeline across iterations.
 * - `Pipeline.loop {}` feeds each guess back as the next input.
 * - Each Loop iteration re-runs the oracle (which mutates its closure)
 *   and re-invokes the guesser with the freshly-overridden prompt.
 *
 * Educational framing: a deterministic Kotlin oracle "teaches" the
 * student agent over time via prompt updates.
 */
class GuessingGameTest {

    /**
     * Build a stub `ModelClient` that parses the system message for a
     * `[low, high]` window and emits the midpoint as text. Stand-in for an
     * actual LLM-driven student; lets the test exercise the real
     * agentic-loop path (which is what `wrap` overrides) without needing
     * a live LLM.
     *
     * #1707/#3 update: the previous version of this test used pure-Kotlin
     * `implementedBy` skills and read `guesserAgent.prompt` inside the
     * lambda via a lateinit capture. That only worked because `wrap` used
     * to mutate `student.prompt`; the mutation was a concurrency hazard
     * (see WrapConcurrencyTest). The current `wrap` threads the override
     * through `executeAgentic` instead — so the override is visible to
     * agentic skills, not to `implementedBy` lambdas. This matches the
     * intended consumer use case: a wrapped student is meaningful when
     * it's LLM-driven.
     */
    private fun midpointStubClient() = ModelClient { msgs ->
        val systemContent = msgs.firstOrNull { it.role == "system" }?.content.orEmpty()
        val match = Regex("""\[(\d+),\s*(\d+)]""").find(systemContent)
        val a = match?.groupValues?.get(1)?.toInt() ?: 1
        val b = match?.groupValues?.get(2)?.toInt() ?: 100
        LlmResponse.Text(((a + b) / 2).toString())
    }

    @Test
    fun `wrap inside Loop — oracle teaches guesser to find secret 42 via binary search`() {
        val secret = 42
        var low = 1
        var high = 100
        val guessLog = mutableListOf<Int>()

        // Oracle: holds the secret + window state in closure. Maps the
        // most recent guess to an updated system prompt for the guesser.
        val oracle = agent<String, String>("oracle") {
            skills {
                skill<String, String>("teach", "Narrow the window and emit a new prompt") {
                    implementedBy { latestGuess ->
                        val parsed = latestGuess.trim().toIntOrNull()
                        if (parsed != null) {
                            guessLog += parsed
                            if (parsed < secret) low = maxOf(low, parsed + 1)
                            else if (parsed > secret) high = minOf(high, parsed - 1)
                        }
                        // Encode the window in a prompt format the guesser can parse.
                        "GUESS — pick the midpoint of [$low, $high]. Reply with ONLY the integer."
                    }
                }
            }
        }

        // Guesser: agentic with a stub `ModelClient` that reads the
        // teacher-supplied system message and emits the midpoint.
        val guesser = agent<String, String>("guesser") {
            prompt("baked-in default — overridden by the oracle every round")
            model { ollama("stub"); client = midpointStubClient() }
            skills {
                skill<String, String>("guess", "Emit the midpoint of the current window") { tools() }
            }
        }

        val game = (oracle wrap guesser).loop(maxIterations = 12) { guess ->
            if (guess.trim() == secret.toString()) null else guess
        }

        val finalGuess = game("start")

        assertEquals(secret.toString(), finalGuess.trim())
        assertTrue(
            guessLog.size <= 7,
            "binary search of 1..100 should converge in ≤ 7 rounds; took ${guessLog.size}: $guessLog",
        )
        assertTrue(guessLog.isNotEmpty(), "guesser must have made at least one guess")
        assertTrue(low <= secret && secret <= high, "secret must always sit inside the running window")
        // Student's baked-in prompt was never mutated (#1707/#3 fix); the
        // wrap override threads through invocation context. Verify the
        // field is still the original baked-in value.
        assertTrue(
            "baked-in default" in guesser.prompt,
            "guesser.prompt must remain the baked-in value (no mutation); got: ${guesser.prompt}",
        )
    }

    @Test
    fun `wrap inside Loop — same oracle and guesser converge for a different secret`() {
        // Sanity: the composition isn't accidentally hardcoded to 42.
        val secret = 7
        var low = 1
        var high = 100

        val oracle = agent<String, String>("oracle-2") {
            skills {
                skill<String, String>("teach", "Narrow window") {
                    implementedBy { latestGuess ->
                        latestGuess.trim().toIntOrNull()?.let { g ->
                            if (g < secret) low = maxOf(low, g + 1)
                            else if (g > secret) high = minOf(high, g - 1)
                        }
                        "GUESS — pick the midpoint of [$low, $high]."
                    }
                }
            }
        }

        val guesser = agent<String, String>("guesser-2") {
            prompt("init")
            model { ollama("stub"); client = midpointStubClient() }
            skills { skill<String, String>("guess", "Midpoint") { tools() } }
        }

        val game = (oracle wrap guesser).loop(maxIterations = 12) { guess ->
            if (guess.trim() == secret.toString()) null else guess
        }

        assertEquals(secret.toString(), game("start").trim())
    }
}
