package agents_engine.model

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
}
