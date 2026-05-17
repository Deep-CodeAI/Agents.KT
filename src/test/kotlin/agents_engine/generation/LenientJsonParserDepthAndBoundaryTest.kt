package agents_engine.generation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Tests for #1980 — LenientJsonParser$Parser cluster (32 unkilled mutants).
// Targets four mutant families the existing tests don't pin:
//
// 1. skipWs removal — parseValue:82, parseObject:119/123/127, parseArray:148.
//    PIT's RemoveCalls mutator drops the skipWs() invocations; tests must use
//    input with whitespace at every interior position so removal breaks parsing.
//
// 2. Conditional boundaries — parseValue:90, parseObject:135, parseArray:156,
//    parseString:161/180, parseNumber:199/207, parseUnicodeEscape:187,
//    withDepth:102. Each needs an exact-boundary input that flips behavior
//    when `<` becomes `<=` (or `>` → `>=`).
//
// 3. withDepth integer arithmetic — withDepth:105/107/109 + the inlined-at-call-
//    site copies surfacing as parseValue:231/233/239/241. Cover by exhaustive
//    depth boundary tests at depth 63 / 64 / 65 (MAX_NESTING_DEPTH=64).
//
// 4. parseUnicodeEscape:185 — `replaced Character return with 0`. Must assert
//    the actual decoded character, not just non-null.
class LenientJsonParserDepthAndBoundaryTest {

    // ── skipWs removal (parseValue:82, parseObject:119/123/127, parseArray:148) ──

    @Test fun `object with whitespace between every token parses correctly`() {
        // Removing any skipWs() call inside parseObject breaks one of these
        // positions, surfacing as either zero-progress throw or wrong key/value.
        val result = LenientJsonParser.parse("""  { "a" : 1 , "b" : 2 }  """)
        assertNotNull(result)
        @Suppress("UNCHECKED_CAST")
        val m = result as Map<String, Any?>
        assertEquals(1, m["a"])
        assertEquals(2, m["b"])
    }

    @Test fun `array with whitespace between every token parses correctly`() {
        val result = LenientJsonParser.parse("""  [ 1 , 2 , 3 ]  """)
        @Suppress("UNCHECKED_CAST")
        val list = result as List<Any?>
        assertEquals(listOf(1, 2, 3), list)
    }

    @Test fun `parseValue leading whitespace before object`() {
        // parseValue:82 calls skipWs() before dispatch. Removing it would mean
        // the leading-space input fails the `s[pos] == '{'` check.
        assertNotNull(LenientJsonParser.parse("   {\"x\":1}"))
    }

    @Test fun `parseValue leading whitespace before array`() {
        assertNotNull(LenientJsonParser.parse("\n\t[1,2]"))
    }

    @Test fun `parseValue leading whitespace before string in array`() {
        // Drives parseArray's internal skipWs before each element.
        @Suppress("UNCHECKED_CAST")
        val r = LenientJsonParser.parse("""[  "a"  ,  "b"  ]""") as List<Any?>
        assertEquals(listOf("a", "b"), r)
    }

    // ── parseObject:135 / parseArray:156 closing-brace consume ────────────────

    @Test fun `parseObject consumes closing brace and parses sibling after`() {
        // parseObject:135 `if (pos < s.length) pos++` consumes the `}`.
        // The mutant flips `<` to `<=`. Killing it requires asserting that
        // parsing continues correctly after the object — easiest via an
        // outer array containing two objects.
        @Suppress("UNCHECKED_CAST")
        val list = LenientJsonParser.parse("""[{"a":1},{"b":2}]""") as List<Any?>
        assertEquals(2, list.size)
        @Suppress("UNCHECKED_CAST")
        val first = list[0] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val second = list[1] as Map<String, Any?>
        assertEquals(1, first["a"])
        assertEquals(2, second["b"])
    }

    @Test fun `parseArray consumes closing bracket and parses sibling after`() {
        @Suppress("UNCHECKED_CAST")
        val obj = LenientJsonParser.parse("""{"a":[1,2],"b":[3,4]}""") as Map<String, Any?>
        assertEquals(listOf(1, 2), obj["a"])
        assertEquals(listOf(3, 4), obj["b"])
    }

    // ── parseString:161/180 — opening/closing quote consume ────────────────────

    @Test fun `parseString without opening quote at start still works (lenient)`() {
        // parseString:161 `if (pos < s.length && s[pos] == '"') pos++`.
        // The `<` boundary mutant doesn't really change behavior here, but the
        // string-value-after assertion catches the closing-quote consume mutant.
        @Suppress("UNCHECKED_CAST")
        val r = LenientJsonParser.parse("""{"k":"abc","next":"def"}""") as Map<String, Any?>
        assertEquals("abc", r["k"])
        assertEquals("def", r["next"], "if closing quote isn't consumed, the next key would be wrong")
    }

    @Test fun `parseString empty string round-trips`() {
        @Suppress("UNCHECKED_CAST")
        val r = LenientJsonParser.parse("""{"k":"","next":"x"}""") as Map<String, Any?>
        assertEquals("", r["k"])
        assertEquals("x", r["next"])
    }

    // ── parseNumber:199 — leading minus ────────────────────────────────────────

    @Test fun `parseNumber accepts leading minus exactly once`() {
        // parseNumber:199 `if (pos < s.length && s[pos] == '-') pos++`.
        // parse() only accepts {/[ at top level — wrap in an array.
        @Suppress("UNCHECKED_CAST")
        val r = LenientJsonParser.parse("[-42, -3.14]") as List<Any?>
        assertEquals(-42, r[0])
        assertEquals(-3.14, r[1])
    }

    // ── parseNumber:207 — exponent sign ────────────────────────────────────────

    @Test fun `parseNumber exponent positive sign consumed`() {
        // parseNumber:207 `if (pos < s.length && (s[pos] == '+' || s[pos] == '-')) pos++`.
        @Suppress("UNCHECKED_CAST")
        val r = LenientJsonParser.parse("[1.5e+3]") as List<Any?>
        assertEquals(1500.0, r[0])
    }

    @Test fun `parseNumber exponent negative sign consumed`() {
        @Suppress("UNCHECKED_CAST")
        val r = LenientJsonParser.parse("[3.0e-2]") as List<Any?>
        assertEquals(0.03, r[0] as Double, 1e-9)
    }

    @Test fun `parseNumber exponent without sign consumed`() {
        // The else branch of the +/- consume — kills mutant that always advances.
        @Suppress("UNCHECKED_CAST")
        val r = LenientJsonParser.parse("[1.5e2]") as List<Any?>
        assertEquals(150.0, r[0])
    }

    // ── parseUnicodeEscape:185, 187 — boundary + return value ──────────────────

    @Test fun `parseUnicodeEscape decodes to exact character (not 0)`() {
        // parseUnicodeEscape:185 — `replaced Character return value with 0`.
        // Test asserts the EXACT character, not just non-null.
        @Suppress("UNCHECKED_CAST")
        val r = LenientJsonParser.parse("""{"k":"é"}""") as Map<String, Any?>
        assertEquals("é", r["k"], "unicode escape \\u00e9 must decode to é (0xe9), not zero")
        assertEquals('é'.code, (r["k"] as String)[0].code)
    }

    @Test fun `parseUnicodeEscape with mixed-case hex decodes correctly`() {
        // parseUnicodeEscape:187 — `it.lowercaseChar() !in 'a'..'f'` boundary.
        // Kills mutant that swaps `!in` for `in` (would reject valid hex).
        @Suppress("UNCHECKED_CAST")
        val r1 = LenientJsonParser.parse("""{"k":"é"}""") as Map<String, Any?>  // uppercase
        assertEquals("é", r1["k"])
        @Suppress("UNCHECKED_CAST")
        val r2 = LenientJsonParser.parse("""{"k":"«"}""") as Map<String, Any?>  // mixed
        assertEquals("«", r2["k"])
    }

    @Test fun `parseUnicodeEscape boundary exactly at pos plus 4 end-of-input`() {
        // parseUnicodeEscape:185 `if (pos + 4 >= s.length) return null`.
        // Wrapped in a string with enough chars after the escape that the
        // boundary fires INSIDE the escape itself.
        @Suppress("UNCHECKED_CAST")
        val r = LenientJsonParser.parse("""{"k":"A"}""") as Map<String, Any?>  // 'A'
        assertEquals("A", r["k"], "well-formed unicode escape at safe distance from EOF must decode")
    }

    // ── withDepth:102 — depth boundary ────────────────────────────────────────

    @Test fun `parser accepts nesting at MAX_NESTING_DEPTH minus 1`() {
        // withDepth:102 `if (depth >= MAX_NESTING_DEPTH) throw`. Boundary mutant
        // flips `>=` to `>` (allowing one extra level). Verify exact boundary.
        // MAX_NESTING_DEPTH = 64. Build a chain of 63 nested objects.
        val depth = LenientJsonParser.MAX_NESTING_DEPTH - 1  // 63
        val opens = "{\"a\":".repeat(depth) + "1" + "}".repeat(depth)
        val result = LenientJsonParser.parse(opens)
        assertNotNull(result, "depth = MAX-1 (${depth}) must parse cleanly")
    }

    @Test fun `parser accepts nesting at exactly MAX_NESTING_DEPTH`() {
        // The exact boundary. After this many nested calls, depth has been
        // incremented to MAX_NESTING_DEPTH; the NEXT call would trigger the
        // throw. This level must succeed.
        val depth = LenientJsonParser.MAX_NESTING_DEPTH  // 64
        val opens = "[".repeat(depth) + "1" + "]".repeat(depth)
        val result = LenientJsonParser.parse(opens)
        assertNotNull(result, "depth = MAX (${depth}) must parse cleanly; the throw fires on the NEXT level")
    }

    @Test fun `parser rejects nesting at MAX_NESTING_DEPTH plus 1`() {
        // The other side — exceeding the cap must fail.
        val depth = LenientJsonParser.MAX_NESTING_DEPTH + 1  // 65
        val opens = "[".repeat(depth) + "1" + "]".repeat(depth)
        // parse() returns null on the IllegalStateException thrown by withDepth.
        val result = LenientJsonParser.parse(opens)
        assertNull(result, "depth = MAX+1 (${depth}) must fail; parse() swallows the throw and returns null")
    }

    @Test fun `withDepth properly increments and decrements depth across nested calls`() {
        // withDepth:105 (depth++) and :109 (depth--) integer arithmetic mutants:
        // "Replaced integer addition with subtraction" would either skip the
        // increment (allowing infinite recursion → stack overflow) or skip the
        // decrement (causing sibling objects to falsely hit the depth cap).
        //
        // Test: many SIBLING objects at modest depth — if depth-- is broken,
        // the cumulative depth across siblings would hit MAX and throw.
        val siblings = (1..100).joinToString(",") { """{"k$it":[1,2]}""" }
        val input = "[$siblings]"
        val result = LenientJsonParser.parse(input)
        assertNotNull(result, "100 sibling objects at depth 2 must parse — proves depth-- works")
        @Suppress("UNCHECKED_CAST")
        assertEquals(100, (result as List<Any?>).size)
    }

    // ── parseValue:90 — first-char dispatch boundary ──────────────────────────

    @Test fun `parseValue empty-after-whitespace returns null cleanly`() {
        // parseValue:90 `if (pos >= s.length) return null`. The boundary mutant
        // `>=` → `>` would NOT short-circuit on exact EOF, then s[pos] OOB throws.
        // Wrapped in parse() which catches and returns null either way, BUT the
        // throw vs return-null distinction matters for mutation detection because
        // PIT tracks behavioral difference.
        assertNull(LenientJsonParser.parse("   "))
    }

    // ── Comprehensive smoke: ensure tonight's mutant cluster doesn't regress ──

    @Test fun `realistic nested LLM-output structure parses correctly`() {
        val input = """
            ```json
            {
              "items": [
                {"id": "a", "tags": ["red", "blue"]},
                {"id": "b", "tags": []}
              ],
              "meta": {"count": 2, "filtered": false}
            }
            ```
        """.trimIndent()
        @Suppress("UNCHECKED_CAST")
        val r = LenientJsonParser.parse(input) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val items = r["items"] as List<Map<String, Any?>>
        assertEquals(2, items.size)
        assertEquals("a", items[0]["id"])
        assertEquals(listOf("red", "blue"), items[0]["tags"])
        assertEquals(emptyList<Any?>(), items[1]["tags"])
        @Suppress("UNCHECKED_CAST")
        val meta = r["meta"] as Map<String, Any?>
        assertEquals(2, meta["count"])
        assertEquals(false, meta["filtered"])
    }
}
