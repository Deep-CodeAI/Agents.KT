package agents_engine.generation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

// Targeted coverage for parser branches called out in #889 — exponent sign
// consumption (+/-), Long-overflow fallback to 0, truncated unicode escape.
class LenientJsonParserCoverageTest {

    @Test
    fun `exponent with explicit plus sign parses as Double`() {
        // Covers parseNumber's `(s[pos] == '+' || s[pos] == '-')` exponent-sign
        // branch on the '+' arm. Without this consumption, the '+' would be
        // skipped over by the digit loop and the number would parse to 1.0.
        val r = LenientJsonParser.parse("""[1e+5]""") as? List<*>
        assertNotNull(r)
        assertEquals(100000.0, r!![0])
    }

    @Test
    fun `exponent with explicit minus sign parses as Double`() {
        // Symmetric cover for the '-' arm of the same branch.
        val r = LenientJsonParser.parse("""[1e-2]""") as? List<*>
        assertNotNull(r)
        assertEquals(0.01, r!![0])
    }

    @Test
    fun `integer larger than Long_MAX_VALUE falls back to zero`() {
        // Covers parseNumber's `n.toLongOrNull() ?: return 0` fallback —
        // a 25-digit integer overflows Long, so toLongOrNull returns null.
        val r = LenientJsonParser.parse("""[9999999999999999999999999]""") as? List<*>
        assertNotNull(r)
        assertEquals(0, r!![0])
    }

    @Test
    fun `truncated unicode escape near end-of-input returns null`() {
        // Covers parseUnicodeEscape's `if (pos + 4 >= s.length) return null`.
        // A "\u" with fewer than 4 trailing hex chars before EOF must surface
        // the literal 'u' (the parseString fallback) without crashing.
        val r = LenientJsonParser.parse("""["\u123""") as? List<*>
        assertNotNull(r, "truncated unicode escape must parse without throwing")
        // parseString falls back to literal 'u' then keeps reading remaining chars.
        // Exact contents matter less than not crashing — verify the array shape.
        assertEquals(1, r!!.size)
    }

    @Test
    fun `uppercase hex letter F in unicode escape is accepted`() {
        // parseUnicodeEscape uses `it.lowercaseChar() !in 'a'..'f'`. Removing
        // the lowercaseChar transform would reject uppercase F. The parser
        // requires the value to be wrapped in an array/object — bare strings
        // return null. Using a regular string (not raw) so Kotlin doesn't
        // unicode-process the ÿ before the parser sees it.
        val json = "[\"\\u00FF\"]"  // JSON: ["ÿ"] — literal backslash-u-00FF
        val r = LenientJsonParser.parse(json) as? List<*>
        assertNotNull(r)
        assertEquals("ÿ", r!![0])
    }
}
