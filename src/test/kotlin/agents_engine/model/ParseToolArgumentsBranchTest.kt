package agents_engine.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

// Tests for #889 — `parseToolArguments` (OllamaClient.kt:26-69) has 8 branches:
// null / Map / String-empty / String→Map / String→null / String→other (e.g. list) /
// String→scalar / else. The existing OllamaClientIntegrationTest covers only the
// String→list case ("malformed list"). This file covers the other seven, killing
// PIT mutants on each branch.
//
// Internal API tested directly because the function is the conversion shim every
// tool-call argument value goes through; misbehavior on any branch silently
// corrupts tool dispatch.
class ParseToolArgumentsBranchTest {

    @Test
    fun `null raw args produces empty map with no rawArguments and no parseError`() {
        val parsed = parseToolArguments(null)
        assertEquals(emptyMap<String, Any?>(), parsed.arguments)
        assertNull(parsed.rawArguments, "null input → no raw to remember")
        assertNull(parsed.parseError, "null input is canonical (no args), not an error")
    }

    @Test
    fun `map raw args round-trips with String-coerced keys`() {
        val raw = mapOf("name" to "Alice", "count" to 3)
        val parsed = parseToolArguments(raw)
        assertEquals("Alice", parsed.arguments["name"])
        assertEquals(3, parsed.arguments["count"])
        assertNull(parsed.rawArguments, "Map input → no raw text to remember")
        assertNull(parsed.parseError, "valid Map is canonical, not an error")
    }

    @Test
    fun `map with non-String keys gets coerced to String via toString`() {
        // Kills the mutant that swaps `.toString()` for `.javaClass.simpleName` or similar.
        val raw = mapOf(42 to "answer", true to "yes")
        val parsed = parseToolArguments(raw)
        assertEquals("answer", parsed.arguments["42"], "non-String keys must be toString-coerced")
        assertEquals("yes", parsed.arguments["true"])
    }

    @Test
    fun `empty string preserves the raw value and produces no error`() {
        // OllamaClient.kt:42-43 — `trimmed.isEmpty()` branch. Kills the mutant
        // that flips the condition to .isNotEmpty() (would route to parser
        // path which then errors on empty input).
        val parsed = parseToolArguments("")
        assertEquals(emptyMap<String, Any?>(), parsed.arguments)
        assertEquals("", parsed.rawArguments, "empty string raw value preserved")
        assertNull(parsed.parseError, "empty string is canonical empty-args, not an error")
    }

    @Test
    fun `whitespace-only string is treated as empty`() {
        // .trim().isEmpty() branch — kills the mutant that removes the .trim() call.
        val parsed = parseToolArguments("   \n\t  ")
        assertEquals(emptyMap<String, Any?>(), parsed.arguments)
        assertEquals("   \n\t  ", parsed.rawArguments, "raw is the ORIGINAL untrimmed string")
        assertNull(parsed.parseError)
    }

    @Test
    fun `valid JSON object string parses canonically`() {
        // The happy path for the LLM emitting `arguments: "{\"name\":\"Alice\"}"`.
        val parsed = parseToolArguments("""{"name": "Alice", "count": 3}""")
        assertEquals("Alice", parsed.arguments["name"])
        assertEquals(3, parsed.arguments["count"])
        assertEquals("""{"name": "Alice", "count": 3}""", parsed.rawArguments)
        assertNull(parsed.parseError)
    }

    @Test
    fun `JSON string that parses to null surfaces a parse-error message`() {
        // Lenient parser returns null on garbage. Kills the mutant that
        // swaps the error message OR removes the parseError field.
        val parsed = parseToolArguments("not valid json at all")
        assertEquals(emptyMap<String, Any?>(), parsed.arguments)
        assertEquals("not valid json at all", parsed.rawArguments, "raw preserved for repair-loop replay")
        assertNotNull(parsed.parseError)
        assert(parsed.parseError!!.contains("JSON object", ignoreCase = true)) {
            "error message should explain the expected shape: '${parsed.parseError}'"
        }
    }

    @Test
    fun `JSON string that parses to a non-object surfaces a parse-error message`() {
        // The case the existing integration test covers — but specifically the
        // SCALAR case (parser returns an Int / Double / Boolean / String), not
        // the list case. Catches the mutant that conflates "parsed-to-non-map"
        // with "parse-failed" (different code paths, different errors).
        val parsed = parseToolArguments("42")
        assertEquals(emptyMap<String, Any?>(), parsed.arguments)
        assertEquals("42", parsed.rawArguments, "raw preserved for repair-loop replay")
        assertNotNull(parsed.parseError)
        assert(parsed.parseError!!.contains("JSON object")) {
            "error should explain that scalars are rejected: '${parsed.parseError}'"
        }
    }

    @Test
    fun `arbitrary non-String non-Map non-null value surfaces a generic parse error`() {
        // The `else` branch at line 64-68. Catches the mutant that removes the
        // `rawArguments = rawArgs.toString()` field assignment.
        val parsed = parseToolArguments(42)
        assertEquals(emptyMap<String, Any?>(), parsed.arguments)
        assertEquals("42", parsed.rawArguments, "raw is the .toString() of the input")
        assertNotNull(parsed.parseError)
        assert(parsed.parseError!!.contains("JSON object")) {
            "error should explain: '${parsed.parseError}'"
        }
    }

    @Test
    fun `arbitrary non-String non-Map non-null value also handles List`() {
        // Lists arriving as the raw value (not as a JSON-string-containing-list)
        // hit the same `else` branch. Different from the String-with-list-content
        // case the existing integration test covers.
        val parsed = parseToolArguments(listOf("Alice", "Bob"))
        assertEquals(emptyMap<String, Any?>(), parsed.arguments)
        assertNotNull(parsed.rawArguments)
        assertNotNull(parsed.parseError)
    }
}
