package agents_engine.generation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * #1028 — `LenientJsonParser` infinite-loop / OOM regression guard.
 *
 * In 0.2.2, `parseValue()` fell through to `parseNumber()` on any unrecognized
 * character. `parseNumber()` returned 0 without advancing `pos` when the input
 * had no digits. `parseArray()` / `parseObject()` spun forever, growing the
 * accumulator until the heap was exhausted.
 *
 * The fix has two layers:
 *   1. `parseValue()` throws on a non-JSON-prefix char instead of falling
 *      through to `parseNumber()`. Caught by `parse(input)` → returns null.
 *   2. `parseArray()` / `parseObject()` throw on zero-progress in their loop
 *      body — defense-in-depth against any future regression.
 *
 * These tests are bounded by `Thread`-side time guards because a regression
 * would manifest as OOM (slow) rather than as an assertion failure.
 */
class LenientJsonParserOomGuardTest {

    private fun assertCompletesIn(maxMs: Long, block: () -> Any?): Any? {
        var result: Any? = null
        val thread = Thread { result = block() }
        thread.isDaemon = true
        thread.start()
        thread.join(maxMs)
        if (thread.isAlive) {
            // Don't try to interrupt — the bug spins on heap allocation, not on a
            // checkpoint that responds to interrupts. Just fail the test.
            throw AssertionError("LenientJsonParser did not complete within ${maxMs}ms — likely an infinite loop")
        }
        return result
    }

    @Test
    fun `array with non-JSON content does not OOM`() {
        assertNull(assertCompletesIn(2_000) { LenientJsonParser.parse("[abc]") })
        assertNull(assertCompletesIn(2_000) { LenientJsonParser.parse("[<html>]") })
        assertNull(assertCompletesIn(2_000) { LenientJsonParser.parse("[1, abc, 3]") })
    }

    @Test
    fun `object with unquoted bare-word value does not OOM`() {
        assertNull(assertCompletesIn(2_000) { LenientJsonParser.parse("{\"k\": abc}") })
        assertNull(assertCompletesIn(2_000) { LenientJsonParser.parse("{\"x\": <html>, \"y\": 1}") })
    }

    @Test
    fun `nested unquoted bare-word values do not OOM`() {
        assertNull(assertCompletesIn(2_000) { LenientJsonParser.parse("{\"outer\": [abc, def]}") })
        assertNull(assertCompletesIn(2_000) { LenientJsonParser.parse("[[abc], [def]]") })
    }

    @Test
    fun `legitimate parses still work`() {
        assertEquals(listOf(1, 2, 3), LenientJsonParser.parse("[1, 2, 3]"))
        assertEquals(mapOf("k" to "v"), LenientJsonParser.parse("{\"k\":\"v\"}"))
        assertEquals(listOf("a", "b"), LenientJsonParser.parse("[\"a\", \"b\"]"))
        assertEquals(listOf(1, "two", true, null), LenientJsonParser.parse("[1, \"two\", true, null]"))
        assertEquals(emptyList<Any?>(), LenientJsonParser.parse("[]"))
        assertEquals(emptyMap<String, Any?>(), LenientJsonParser.parse("{}"))
        assertEquals(mapOf("nested" to listOf(1, 2)), LenientJsonParser.parse("{\"nested\":[1, 2]}"))
    }

    @Test
    fun `negative numbers and decimals still parse`() {
        assertEquals(listOf(-1, -2.5), LenientJsonParser.parse("[-1, -2.5]"))
        assertEquals(mapOf("temp" to -40), LenientJsonParser.parse("{\"temp\":-40}"))
    }
}
