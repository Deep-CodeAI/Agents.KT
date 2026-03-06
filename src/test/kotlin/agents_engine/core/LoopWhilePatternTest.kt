package agents_engine.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Tests for while(condition from agent) { pipeline } patterns.
 *
 * The loop block IS the while condition — it receives the pipeline output
 * and returns the next input to continue, or null to stop.
 *
 *   while (result.needsWork) { result = pipeline(result) }
 *   ↕
 *   pipeline.loop { result -> if (result.needsWork) result else null }
 */
class LoopWhilePatternTest {

    // ─── State carries the stop signal ───

    data class WorkItem(val value: Int, val done: Boolean)

    @Test
    fun `while not done run pipeline`() {
        // while (!item.done) { item = process(item) }
        val process = agent<WorkItem, WorkItem>("process") {
            execute { item ->
                val next = item.value + 1
                WorkItem(next, next >= 5)
            }
        }

        val loop = process.loop { result -> if (result.done) null else result }

        assertEquals(WorkItem(5, true), loop(WorkItem(0, false)))
    }

    // ─── Accumulator pattern ───

    data class State(val remaining: Int, val total: Int)

    @Test
    fun `while remaining run accumulating pipeline`() {
        // while (state.remaining > 0) { state = step(state) }
        val step = agent<State, State>("step") {
            execute { s -> State(s.remaining - 1, s.total + s.remaining) }
        }

        val loop = step.loop { result -> if (result.remaining <= 0) null else result }

        // sum 1..5 = 15
        assertEquals(State(0, 15), loop(State(5, 0)))
    }

    // ─── Condition checked by a separate agent ───

    @Test
    fun `while checker agent says keep going run pipeline`() {
        // The next block is plain Kotlin — can call any agent or function inline
        val checker = agent<Int, Boolean>("checker") { execute { it < 10 } }
        val body    = agent<Int, Int>("body")        { execute { it * 2 } }

        val loop = body.loop { result ->
            if (checker(result)) result else null   // checker is invoked as a function
        }

        // 1 → 2 → 4 → 8 → 16 (stop, 16 >= 10)
        assertEquals(16, loop(1))
    }

    // ─── Multi-agent pipeline body ───

    @Test
    fun `while condition run multi-step pipeline body`() {
        // while (result < 100) { result = (normalize then amplify)(result) }
        data class Signal(val value: Double)

        val normalize = agent<Signal, Signal>("normalize") { execute { Signal(it.value + 1.0) } }
        val amplify   = agent<Signal, Signal>("amplify")   { execute { Signal(it.value * 1.5) } }

        val pipeline = normalize then amplify
        val loop = pipeline.loop { result -> if (result.value >= 10.0) null else result }

        val result = loop(Signal(1.0))
        assert(result.value >= 10.0) { "Expected >= 10.0, got ${result.value}" }
    }

    // ─── Retry pattern ───

    @Test
    fun `retry pipeline until result is acceptable`() {
        var attempts = 0
        val attempt = agent<String, Int>("attempt") {
            execute { _ -> attempts++; attempts }   // returns attempt number
        }

        // retry until attempt 3
        val loop = attempt.loop { result -> if (result >= 3) null else "retry" }

        assertEquals(3, loop("start"))
        assertEquals(3, attempts)
    }

    // ─── Loop with transformation between iterations ───

    @Test
    fun `next block transforms output back to different input type`() {
        data class Raw(val text: String)
        data class Processed(val words: List<String>, val needsMore: Boolean)

        val process = agent<Raw, Processed>("process") {
            execute { raw ->
                val words = raw.text.split(" ")
                Processed(words, words.size < 4)
            }
        }

        val loop = process.loop { result ->
            if (!result.needsMore) null
            else Raw(result.words.joinToString(" ") + " extra")
        }

        // "a b" → 2 words, needs more → "a b extra" → 3 words, needs more
        // → "a b extra extra" → 4 words, done
        val result = loop(Raw("a b"))
        assertEquals(4, result.words.size)
    }
}
