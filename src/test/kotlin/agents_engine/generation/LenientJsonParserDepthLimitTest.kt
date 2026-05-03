package agents_engine.generation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

// Tests for #854 — LenientJsonParser caps nesting depth so a malicious payload
// can't overflow the JVM stack.
class LenientJsonParserDepthLimitTest {

    @Test
    fun `extreme nesting returns null instead of crashing with StackOverflowError`() {
        // 10 000 levels — would overflow without the depth cap.
        val deep = "{" + "\"a\":{".repeat(10_000) + "1" + "}".repeat(10_001)
        assertNull(LenientJsonParser.parse(deep), "extreme nesting must return null, not throw")
    }

    @Test
    fun `nesting at exactly the cap parses successfully`() {
        // The cap is on Parser internals — depth counts each open `{`/`[` Parser
        // descends into. With MAX_NESTING_DEPTH = 64, 63 nested objects + leaf parse.
        val depth = LenientJsonParser.MAX_NESTING_DEPTH - 1
        val capJson = "{" + "\"a\":{".repeat(depth - 1) + "\"x\":1" + "}".repeat(depth)
        val r = LenientJsonParser.parse(capJson)
        assertNotNull(r, "at-cap input must parse")
    }

    @Test
    fun `nesting one past the cap returns null`() {
        val depth = LenientJsonParser.MAX_NESTING_DEPTH + 1
        val overCap = "{" + "\"a\":{".repeat(depth - 1) + "\"x\":1" + "}".repeat(depth)
        assertNull(LenientJsonParser.parse(overCap), "over-cap input must return null")
    }

    @Test
    fun `array nesting is also capped`() {
        val depth = LenientJsonParser.MAX_NESTING_DEPTH + 50
        val deepArray = "[".repeat(depth) + "1" + "]".repeat(depth)
        assertNull(LenientJsonParser.parse(deepArray), "deeply-nested array must return null")
    }

    @Test
    fun `mixed object array nesting at moderate depth still parses`() {
        // 10 levels mixed — well under the cap.
        val ten = LenientJsonParser.parse("""{"a":[{"b":[{"c":[{"d":[{"e":42}]}]}]}]}""") as? Map<*, *>
        assertNotNull(ten, "moderate mixed nesting must parse fine")
    }

    @Test
    fun `flat structures with many siblings are unaffected (siblings dont count as depth)`() {
        // 10 000 sibling fields — no depth recursion, just iteration.
        val flat = buildString {
            append("{")
            (0 until 10_000).joinTo(this, ",") { """"k$it":$it""" }
            append("}")
        }
        val r = LenientJsonParser.parse(flat) as? Map<*, *>
        assertNotNull(r, "wide-but-shallow input must parse")
        assertEquals(10_000, r!!.size)
    }
}
