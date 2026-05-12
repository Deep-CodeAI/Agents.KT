package agents_engine.composition.wrap

import agents_engine.composition.loop.loop
import agents_engine.core.Agent
import agents_engine.core.agent
import agents_engine.core.skill
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

        // Guesser: reads its runtime prompt (set by wrap each round) and
        // emits the midpoint of the window. Captured via lateinit so the
        // closure can read the live `prompt` at invocation time.
        lateinit var guesserAgent: Agent<String, String>
        guesserAgent = agent<String, String>("guesser") {
            prompt("baked-in default — overridden by the oracle every round")
            skills {
                skill<String, String>("guess", "Emit the midpoint of the current window") {
                    implementedBy { _ ->
                        // The wrap override is reflected in the agent's `prompt`
                        // for the duration of this call. Parse "[A, B]" out of
                        // the override; default to [1, 100] if missing.
                        val windowRegex = Regex("""\[(\d+),\s*(\d+)]""")
                        val match = windowRegex.find(guesserAgent.prompt)
                        val a = match?.groupValues?.get(1)?.toInt() ?: 1
                        val b = match?.groupValues?.get(2)?.toInt() ?: 100
                        ((a + b) / 2).toString()  // binary-search midpoint
                    }
                }
            }
        }

        // Compose: wrap → Pipeline → loop. Termination is "guess matches secret."
        val game = (oracle wrap guesserAgent).loop(maxIterations = 12) { guess ->
            if (guess.trim() == secret.toString()) null else guess
        }

        val finalGuess = game("")  // start with empty input — oracle initialises [1, 100]

        // 1. Final guess is the secret.
        assertEquals(secret.toString(), finalGuess.trim())

        // 2. Binary search of 1..100 converges in ≤ 7 rounds (⌈log2(100)⌉).
        assertTrue(
            guessLog.size <= 7,
            "binary search of 1..100 should converge in ≤ 7 rounds; took ${guessLog.size}: $guessLog",
        )
        assertTrue(guessLog.isNotEmpty(), "guesser must have made at least one guess")

        // 3. Window is fully narrowed at termination.
        assertTrue(low <= secret && secret <= high, "secret must always sit inside the running window")

        // 4. Student's baked-in prompt is restored after the whole game,
        //    not just per round — the override unwinds cleanly across
        //    the entire loop chain.
        assertTrue(
            "baked-in default" in guesserAgent.prompt,
            "after the game, guesser.prompt must be restored to the baked-in default; got: ${guesserAgent.prompt}",
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

        lateinit var guesserAgent: Agent<String, String>
        guesserAgent = agent<String, String>("guesser-2") {
            prompt("init")
            skills {
                skill<String, String>("guess", "Midpoint") {
                    implementedBy { _ ->
                        val match = Regex("""\[(\d+),\s*(\d+)]""").find(guesserAgent.prompt)
                        val a = match?.groupValues?.get(1)?.toInt() ?: 1
                        val b = match?.groupValues?.get(2)?.toInt() ?: 100
                        ((a + b) / 2).toString()
                    }
                }
            }
        }

        val game = (oracle wrap guesserAgent).loop(maxIterations = 12) { guess ->
            if (guess.trim() == secret.toString()) null else guess
        }

        assertEquals(secret.toString(), game("").trim())
    }
}
