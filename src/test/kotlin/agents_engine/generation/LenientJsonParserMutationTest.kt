package agents_engine.generation

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Mutation-killer tests for LenientJsonParser — see #839.
// Targets specific surviving mutants from the #836 baseline; comments cite line+mutator.
class LenientJsonParserMutationTest {

    // parseObject / parseArray — VoidMethodCall on skipWs() (L75/77/79/81, L94/97)
    // Each removed skipWs survived because tests use minified inputs. Cover whitespace
    // at every interior position.

    @Test
    fun `parses object with whitespace at every interior position`() {
        // Whitespace immediately after `{`, around key, around `:`, around value,
        // around `,`, before `}`. Removing any skipWs() would mis-position the cursor.
        val raw = "{   \"a\"   :   1   ,   \"b\"   :   \"x\"   }"
        val result = LenientJsonParser.parse(raw) as? Map<*, *>
        assertNotNull(result)
        assertEquals(1, result!!["a"])
        assertEquals("x", result["b"])
    }

    @Test
    fun `parses array with whitespace at every interior position`() {
        val raw = "[   1   ,   2   ,   \"x\"   ]"
        val result = LenientJsonParser.parse(raw) as? List<*>
        assertEquals(listOf(1, 2, "x"), result)
    }

    @Test
    fun `parses object with whitespace before key only`() {
        val result = LenientJsonParser.parse("{    \"k\":1}") as? Map<*, *>
        assertEquals(1, result!!["k"])
    }

    @Test
    fun `parses object with whitespace after value only`() {
        val result = LenientJsonParser.parse("{\"k\":1    }") as? Map<*, *>
        assertEquals(1, result!!["k"])
    }

    // parseNumber — ConditionalsBoundary on L144-153, plus the `-` sign branch

    @Test
    fun `negative number with trailing field is parsed correctly`() {
        // Forces the `s[pos] == '-'` branch to execute exactly once and
        // exercises the `pos < s.length` boundary on each digit.
        val r = LenientJsonParser.parse("""{"a": -42, "b": 7}""") as? Map<*, *>
        assertEquals(-42, r!!["a"])
        assertEquals(7, r["b"])
    }

    @Test
    fun `positive number is NOT incremented as if it were minus`() {
        // L144 mutation removes the `s[pos]=='-' → pos++`. Without proper guarding,
        // mutated code could over-increment on a digit. Assert the leading digit is
        // preserved (would become "23" if pos++ ran on the '1' of "123").
        val r = LenientJsonParser.parse("""{"x": 123}""") as? Map<*, *>
        assertEquals(123, r!!["x"])
    }

    @Test
    fun `number with fractional part and exponent rounds-trips correctly`() {
        // Exercises the `.` branch (L146-148) and the `eE` branch (L150-153).
        val r = LenientJsonParser.parse("""{"d": 1.5e2}""") as? Map<*, *>
        assertEquals(150.0, r!!["d"])
    }

    @Test
    fun `number ends exactly at end of input`() {
        // Boundary case for `pos < s.length` on every loop in parseNumber.
        // No trailing whitespace, no closing brace after the number.
        val r = LenientJsonParser.parse("[42]") as? List<*>
        assertEquals(listOf(42), r)
    }

    @Test
    fun `Int Long boundary is preserved`() {
        // parseNumber chooses Int vs Long around Int.MAX_VALUE (L161).
        // Int.MAX_VALUE = 2147483647 → fits Int.
        // Int.MAX_VALUE + 1 = 2147483648 → must stay Long.
        val r = LenientJsonParser.parse("""{"i": 2147483647, "l": 2147483648}""") as? Map<*, *>
        assertEquals(2147483647, r!!["i"])
        assertTrue(r["i"] is Int, "MAX_VALUE must be Int, got ${r["i"]?.javaClass}")
        assertEquals(2147483648L, r["l"])
        assertTrue(r["l"] is Long, "MAX_VALUE+1 must stay Long, got ${r["l"]?.javaClass}")
    }

    // parseString — ConditionalsBoundary on L106/108/109/125, MathMutator on L109

    @Test
    fun `parses empty string`() {
        // L108 boundary — the loop must terminate immediately when s[pos]=='"'.
        val r = LenientJsonParser.parse("""{"s": ""}""") as? Map<*, *>
        assertEquals("", r!!["s"])
    }

    @Test
    fun `string with backslash escape applied to special char`() {
        // L109 MathMutator on `pos + 1 < s.length`. With the escape at the very end,
        // boundary off-by-one would either mis-handle or skip.
        val r = LenientJsonParser.parse("""{"k": "a\\b"}""") as? Map<*, *>
        assertEquals("a\\b", r!!["k"])
    }

    // parseUnicodeEscape — ConditionalsBoundary L130/132, MathMutator L130

    @Test
    fun `unicode escape decodes to exact codepoint value`() {
        // MathMutator on L130 (`pos + 4 < s.length` — `+4` mutated to `-4`). Even if
        // the parser doesn't crash, returning a wrong char value or `null` flag would
        // change the resulting string. Assert the decoded codepoint exactly.
        val r = LenientJsonParser.parse("""{"c": "A"}""") as? Map<*, *>
        assertEquals("A", r!!["c"])
    }

    @Test
    fun `invalid hex in unicode escape falls back to literal u and continues parsing`() {
        // L132 boundary — `if (hex.any { ... !in 'a'..'f' }) return null`. When
        // parseUnicodeEscape returns null, the literal 'u' is appended; pos is NOT
        // advanced past the hex chars, so the Z's are consumed as regular chars.
        val r = LenientJsonParser.parse("""{"c": "\uZZZZ"}""") as? Map<*, *>
        assertEquals("uZZZZ", r!!["c"])
    }

    // parseObject / parseArray — empty-with-whitespace kills the L75 / L94 skipWs.
    // Without the post-`{` skipWs, the while-loop enters with cursor on space, then
    // parseString consumes the eventual `}` as a key, returning {"}":null} instead.

    @Test
    fun `empty object with internal whitespace returns empty map`() {
        // L75 VoidMethodCall — skipWs() right after consuming `{`. Removing it makes
        // the while-loop misinterpret the closing brace as a key.
        val r = LenientJsonParser.parse("{   }") as? Map<*, *>
        assertNotNull(r)
        assertEquals(emptyMap<String, Any?>(), r)
    }

    @Test
    fun `empty array with internal whitespace returns empty list`() {
        // L94 VoidMethodCall — same shape for parseArray.
        val r = LenientJsonParser.parse("[   ]") as? List<*>
        assertNotNull(r)
        assertEquals(emptyList<Any?>(), r)
    }

    // parseNumber — L144/145/146/148/150/152/153 ConditionalsBoundary on `pos < s.length`.
    // To exercise these, the number must end at exact end-of-input. Since parse()
    // catches exceptions from the inner Parser, an `<=` mutation on the bounds-check
    // would crash on `s[len]` and parse() returns null instead of the partial value.

    @Test
    fun `integer immediately at end of input parses successfully (no crash)`() {
        // Truncated input forces pos==len during parseNumber's digit loop (L145).
        // With `<=` mutation, accessing s[len] crashes → parse returns null.
        val r = LenientJsonParser.parse("""{"n":12""") as? Map<*, *>
        assertNotNull(r, "Truncated input must still parse — bounds-check is `<`")
        assertEquals(12, r!!["n"])
    }

    @Test
    fun `decimal immediately at end of input parses successfully (no crash)`() {
        // Hits L146 (the `.` boundary) and L148 (fractional digits boundary).
        val r = LenientJsonParser.parse("""[1.5""") as? List<*>
        assertNotNull(r)
        assertEquals(1.5, r!![0])
    }

    @Test
    fun `exponent immediately at end of input parses successfully (no crash)`() {
        // Hits L150/L152/L153 — the `eE`, `+/-`, and exponent-digit boundaries.
        val r = LenientJsonParser.parse("""[1e2""") as? List<*>
        assertNotNull(r)
        assertEquals(100.0, r!![0])
    }

    @Test
    fun `negative-only number at end of input is treated as zero`() {
        // L156 `if (n.isEmpty() || n == "-") return 0`. This is reached when pos
        // advanced past `-` but no digit followed. Forces the L144 boundary too.
        val r = LenientJsonParser.parse("""[-""") as? List<*>
        assertNotNull(r)
        assertEquals(0, r!![0])
    }

    @Test
    fun `capital E exponent is parsed as Double`() {
        // L157 `'e' in n.lowercase()`. Removing `.lowercase()` would make case-sensitive
        // so `1E2` would fall through to Long parsing, fail, and return 0.
        val r = LenientJsonParser.parse("""[1E2]""") as? List<*>
        assertNotNull(r)
        assertEquals(100.0, r!![0])
    }

    @Test
    fun `Int MIN_VALUE boundary preserved as Int`() {
        // L161 `if (l >= Int.MIN_VALUE && l <= Int.MAX_VALUE)`. Mutation on `>=`
        // would make Int.MIN_VALUE NOT match, returning Long.
        val r = LenientJsonParser.parse("""{"min":-2147483648}""") as? Map<*, *>
        assertNotNull(r)
        assertTrue(r!!["min"] is Int, "Int.MIN_VALUE must stay Int, got ${r["min"]?.javaClass}")
        assertEquals(-2147483648, r["min"])
    }

    // parseString — unclosed-at-EOF and backslash-at-EOF kill L108/L109 boundaries.

    @Test
    fun `unclosed string at end of input parses what was read`() {
        // L108 boundary `while (pos < s.length && s[pos] != '"')` — with `<=`, the
        // s[len] access would throw and parse returns null.
        val r = LenientJsonParser.parse("[\"abc") as? List<*>
        assertNotNull(r, "Truncated string must parse partial — bound is `<` not `<=`")
        assertEquals(listOf("abc"), r)
    }

    @Test
    fun `backslash at very end of input falls through without accessing past end`() {
        // L109 MathMutator on `pos + 1 < s.length`. With `+1` → `-1`, the condition
        // erroneously enters the escape branch which then reads s[pos++] past EOF.
        val r = LenientJsonParser.parse("[\"\\") as? List<*>
        assertNotNull(r, "Backslash at end of input must NOT enter the escape branch")
        assertEquals(listOf("\\"), r)
    }

    // findMatchingClose — IncrementsMutator on depth++/depth-- (L45/47)

    @Test
    fun `deeply nested object reaches matching close at the right depth`() {
        // depth must go 0→1→2→3→2→1→0. Swapping ++ and -- would never reach 0
        // and the function would return -1, making the parser substring run to end.
        val r = LenientJsonParser.parse("""{"a":{"b":{"c":42}}}""") as? Map<*, *>
        assertNotNull(r)
        val a = r!!["a"] as Map<*, *>
        val b = a["b"] as Map<*, *>
        assertEquals(42, b["c"])
    }

    @Test
    fun `mixed nested object and array close-matching is depth-correct`() {
        // {"a":[{"b":1},{"b":2}]} — opens {, [, {, }, {, }, ], }
        val r = LenientJsonParser.parse("""{"a":[{"b":1},{"b":2}]}""") as? Map<*, *>
        val a = r!!["a"] as List<*>
        assertEquals(2, a.size)
        assertEquals(1, (a[0] as Map<*, *>)["b"])
        assertEquals(2, (a[1] as Map<*, *>)["b"])
    }

    // extractJsonBlock — EmptyObjectReturnVals on L27

    @Test
    fun `plain text input returns null and does not return empty string`() {
        // L27: `if (start < 0) return stripped.trim()`. Mutated to `return ""` would
        // also propagate to parse() returning null (since "" doesn't start with { or [).
        // Distinguish by passing input where the trimmed text DOES start with { but
        // is otherwise corrupt — actually that's a different code path.
        // Instead, just assert: input "no json here" → parse returns null AND
        // input "  no json here  " → also null (trim happened).
        assertNull(LenientJsonParser.parse("no json here"))
        assertNull(LenientJsonParser.parse("    "))
    }

    @Test
    fun `string with explanatory prefix and suffix returns just the JSON value`() {
        // Forces extractJsonBlock to find { and use findMatchingClose. If
        // findMatchingClose returned -1 (mutated depth ++/-- swap), the block
        // would extend to end of input and parsing would still succeed but
        // include the trailing text as part of the parse — fails differently.
        val r = LenientJsonParser.parse("Result: {\"v\":1} suffix text") as? Map<*, *>
        assertEquals(1, r!!["v"])
    }
}
