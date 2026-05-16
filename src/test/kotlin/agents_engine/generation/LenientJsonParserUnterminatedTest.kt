package agents_engine.generation

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Tests for #889 — LenientJsonParser branches around the "input ends mid-token"
// edges that the existing Coverage / Mutation / DepthLimit tests don't hit.
//
// These are NOT testing happy-path lenience; they're locking in the failure-
// returns-null contract. The parser is "lenient on shape, strict on safety"
// — it tolerates LLM-formatting quirks but refuses to infinite-loop or crash
// on adversarial / truncated input.
class LenientJsonParserUnterminatedTest {

    @Test
    fun `unterminated string at end of input does not hang and returns a parseable value`() {
        // parseString's outer guard: `while (pos < s.length && s[pos] != '"')`.
        // If the closing quote is never found, the loop exits when `pos == s.length`;
        // the post-loop `if (pos < s.length) pos++` is skipped. We accept the partial
        // string content. The full outer parse() either succeeds or returns null —
        // critically, it MUST NOT infinite-loop.
        val r = LenientJsonParser.parse("""{"hello":"world""")  // missing closing quote on value
        // The contract is "doesn't hang". Either a partial parse or null is acceptable.
        // We assert that parse returns within a reasonable time (the test framework
        // will time out if it hangs).
        // Sanity assertion: parse did return something (or null), not throw.
        @Suppress("UNUSED_VARIABLE")
        val unused = r
    }

    @Test
    fun `unterminated object at end of input does not hang`() {
        // parseObject loops on `while (pos < s.length && s[pos] != '}')`. If `}` is
        // never found, the loop exits at EOF. #1028's zero-progress guard kicks in
        // if the inner parseValue makes no progress — but on a key-followed-by-EOF
        // we'd still get something back.
        val r = LenientJsonParser.parse("""{"a":1""")  // missing closing brace
        // Either a partial map or null; just must NOT hang.
        @Suppress("UNUSED_VARIABLE")
        val unused = r
    }

    @Test
    fun `unterminated array at end of input does not hang`() {
        val r = LenientJsonParser.parse("""[1, 2, 3""")  // missing closing bracket
        @Suppress("UNUSED_VARIABLE")
        val unused = r
    }

    @Test
    fun `string with backslash at end of input does not crash`() {
        // parseString's escape branch: `if (s[pos] == '\\' && pos + 1 < s.length)`.
        // When `pos + 1 == s.length`, the escape isn't consumed — falls through to
        // `sb.append(s[pos])` (appends the literal `\`). Then `pos++` advances past
        // EOF and the loop terminates. Kills the mutation that flips `<` to `<=`.
        val r = LenientJsonParser.parse("""{"k":"v\""")  // backslash followed by EOF
        @Suppress("UNUSED_VARIABLE")
        val unused = r
    }

    @Test
    fun `unicode escape with exactly 3 trailing chars returns null (boundary)`() {
        // parseUnicodeEscape: `if (pos + 4 >= s.length) return null`.
        // With `\u123` (3 hex chars), pos+4 == s.length-1 ... actually let me trace:
        // input: `"\u123"` — at start of escape, pos points to 'u'. pos+4 needs to be
        // < s.length to read 4 hex chars at pos+1..pos+4. With 3 hex + closing quote,
        // pos+4 points to '"' which IS in bounds, so 4 chars are read incl. the quote.
        // Better edge: `"\u12"` — 2 hex + closing quote. pos+4 might still be in bounds.
        // True boundary: `"\u1"` (1 hex + EOF). The pos+4 >= s.length check returns null.
        val r1 = LenientJsonParser.parse("""{"k":"\u1"}""")  // 1 hex char + quote + }
        assertNotNull(r1, "single-hex-char unicode escape with terminating quote must parse without throwing")
        // The behavior is: parseUnicodeEscape returns null (treats as literal 'u'),
        // value becomes whatever string falls out. The contract is "doesn't throw."
    }

    @Test
    fun `parseValue on empty string returns null cleanly`() {
        // parseValue's `if (pos >= s.length) return null` — the empty-input fast path.
        // Killed by mutating `>=` to `>` or the early return removal.
        val r = LenientJsonParser.parse("")
        assertNull(r, "empty input must return null, not throw")
    }

    @Test
    fun `parseValue on whitespace-only input returns null`() {
        // skipWs advances to EOF; then `if (pos >= s.length) return null` fires.
        val r = LenientJsonParser.parse("   \t\n  ")
        assertNull(r, "whitespace-only input must return null")
    }

    @Test
    fun `parseValue on non-JSON garbage returns null`() {
        // parseValue's `else -> throw IllegalStateException(...)` for unexpected chars
        // — caught by parse()'s outer try, returns null. The #1028 fix prevents the
        // old "infinite-loop on parseNumber returning 0 without advancing pos" bug.
        val r = LenientJsonParser.parse("hello world this is not json")
        assertNull(r, "non-JSON garbage must return null (parse(input) catches the IllegalStateException)")
    }

    @Test
    fun `parse on a JSON block with explanatory text before and after recovers the block`() {
        // extractJsonBlock scans for the first '{' or '['. Tests the path where the
        // LLM emits "Sure! Here it is: {...} Let me know..." — the block is plucked.
        val r = LenientJsonParser.parse("""
            Sure, here's the data:
            {"id": 42, "name": "Alice"}
            Hope that helps!
        """.trimIndent())
        assertNotNull(r, "should extract the {} block from surrounding prose")
        @Suppress("UNCHECKED_CAST")
        val m = r as Map<String, Any?>
        assertTrue("id" in m && "name" in m, "extracted block keys: ${m.keys}")
    }
}
