package agents_engine.model

import agents_engine.generation.LenientJsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class InlineToolCallParserTest {

    @Test
    fun `parses tool name and arguments`() {
        val result = InlineToolCallParser.parse("""{"tool":"greet","arguments":{"name":"world"}}""")
        assertNotNull(result)
        assertEquals("greet", result!!.name)
        assertEquals("world", result.arguments["name"])
    }

    @Test
    fun `returns null for plain text`() {
        assertNull(InlineToolCallParser.parse("Hello world"))
    }

    @Test
    fun `returns null when tool field missing`() {
        assertNull(InlineToolCallParser.parse("""{"arguments":{"name":"world"}}"""))
    }

    @Test
    fun `handles empty arguments`() {
        val result = InlineToolCallParser.parse("""{"tool":"noop","arguments":{}}""")
        assertNotNull(result)
        assertEquals("noop", result!!.name)
        assertEquals(emptyMap<String, Any?>(), result.arguments)
    }

    @Test
    fun `handles missing arguments field`() {
        val result = InlineToolCallParser.parse("""{"tool":"noop"}""")
        assertNotNull(result)
        assertEquals("noop", result!!.name)
        assertEquals(emptyMap<String, Any?>(), result.arguments)
    }

    @Test
    fun `trims whitespace before parsing`() {
        val result = InlineToolCallParser.parse("  \n{\"tool\":\"ping\",\"arguments\":{}}  ")
        assertNotNull(result)
        assertEquals("ping", result!!.name)
    }

    // ── broken JSON ───────────────────────────────────────────────────────────

    @Test
    fun `truncated json is recovered leniently`() {
        // parser stops at end-of-input rather than throwing — partial tool call is still usable
        val result = InlineToolCallParser.parse("""{"tool":"greet","arguments":{"name"""")
        assertNotNull(result)
        assertEquals("greet", result!!.name)
    }

    @Test
    fun `unclosed outer brace is recovered leniently`() {
        val result = InlineToolCallParser.parse("""{"tool":"greet","arguments":{}}""")
        assertNotNull(result)
        assertEquals("greet", result!!.name)
    }

    @Test
    fun `returns null for empty input`() {
        assertNull(InlineToolCallParser.parse(""))
    }

    @Test
    fun `returns null when tool value is a number not a string`() {
        assertNull(InlineToolCallParser.parse("""{"tool":42,"arguments":{}}"""))
    }

    @Test
    fun `returns null when tool value is null`() {
        assertNull(InlineToolCallParser.parse("""{"tool":null,"arguments":{}}"""))
    }

    @Test
    fun `returns null for array root`() {
        assertNull(InlineToolCallParser.parse("""["greet",{}]"""))
    }

    @Test
    fun `returns null for empty object`() {
        assertNull(InlineToolCallParser.parse("{}"))
    }

    @Test
    fun `toJson round-trips file paths and multiline content`() {
        val call = ToolCall(
            name = "write_file",
            arguments = mapOf(
                "path" to """C:\tmp\notes.txt""",
                "content" to "first line\n\tsecond line",
            ),
        )

        val parsed = InlineToolCallParser.parse(InlineToolCallParser.toJson(call))

        assertNotNull(parsed)
        assertEquals("write_file", parsed!!.name)
        assertEquals("""C:\tmp\notes.txt""", parsed.arguments["path"])
        assertEquals("first line\n\tsecond line", parsed.arguments["content"])
    }

    @Test
    fun `toJson round-trips quoted and control characters`() {
        val original = mapOf(
            "quoted" to """say "hello" from C:\temp""",
            "control" to "row1\rrow2\tindent\b\u000C\u0001",
        )

        val parsed = InlineToolCallParser.parse(
            InlineToolCallParser.toJson(ToolCall(name = "echo", arguments = original))
        )

        assertNotNull(parsed)
        assertEquals("echo", parsed!!.name)
        assertEquals(original["quoted"], parsed.arguments["quoted"])
        assertEquals(original["control"], parsed.arguments["control"])
    }

    @Test
    fun `argsToJson preserves nested maps and lists`() {
        val json = InlineToolCallParser.argsToJson(
            mapOf(
                "payload" to mapOf(
                    "path" to """/tmp/demo""",
                    "lines" to listOf("one", "two\nthree"),
                ),
            ),
        )

        val parsed = LenientJsonParser.parse(json) as? Map<*, *>
        val payload = parsed?.get("payload") as? Map<*, *>

        assertNotNull(payload)
        assertEquals("/tmp/demo", payload!!["path"])
        assertEquals(listOf("one", "two\nthree"), payload["lines"])
    }

    @Test
    fun `argsToJson preserves escapes inside nested payloads`() {
        val json = InlineToolCallParser.argsToJson(
            mapOf(
                "payload" to mapOf(
                    "message" to """say "hello" from C:\tmp""",
                    "notes" to listOf("a\rb", "c\t\bd", "\u0001"),
                ),
            ),
        )

        val parsed = LenientJsonParser.parse(json) as? Map<*, *>
        val payload = parsed?.get("payload") as? Map<*, *>

        assertNotNull(payload)
        assertEquals("""say "hello" from C:\tmp""", payload!!["message"])
        assertEquals(listOf("a\rb", "c\t\bd", "\u0001"), payload["notes"])
    }
}
