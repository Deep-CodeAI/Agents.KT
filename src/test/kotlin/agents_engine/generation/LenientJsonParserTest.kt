package agents_engine.generation

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LenientJsonParserTest {

    @Test
    fun `parses flat object with string and number`() {
        val result = LenientJsonParser.parse("""{"name": "Alice", "age": 30}""") as? Map<*, *>
        assertNotNull(result)
        assertEquals("Alice", result!!["name"])
        assertEquals(30, result["age"])
    }

    @Test
    fun `parses nested object`() {
        val result = LenientJsonParser.parse("""{"user": {"name": "Bob"}}""") as? Map<*, *>
        val user = result!!["user"] as? Map<*, *>
        assertEquals("Bob", user!!["name"])
    }

    @Test
    fun `parses array of strings`() {
        val result = LenientJsonParser.parse("""["a", "b", "c"]""") as? List<*>
        assertEquals(listOf("a", "b", "c"), result)
    }

    @Test
    fun `parses boolean values`() {
        val result = LenientJsonParser.parse("""{"yes": true, "no": false}""") as? Map<*, *>
        assertEquals(true, result!!["yes"])
        assertEquals(false, result["no"])
    }

    @Test
    fun `parses null value`() {
        val result = LenientJsonParser.parse("""{"nothing": null}""") as? Map<*, *>
        assertNotNull(result)
        assertNull(result!!["nothing"])
    }

    @Test
    fun `parses double value`() {
        val result = LenientJsonParser.parse("""{"score": 0.95}""") as? Map<*, *>
        assertEquals(0.95, result!!["score"])
    }

    @Test
    fun `parses negative number`() {
        val result = LenientJsonParser.parse("""{"delta": -3}""") as? Map<*, *>
        assertEquals(-3, result!!["delta"])
    }

    @Test
    fun `strips markdown json fences`() {
        val result = LenientJsonParser.parse("```json\n{\"key\": \"value\"}\n```") as? Map<*, *>
        assertEquals("value", result!!["key"])
    }

    @Test
    fun `strips plain markdown fences`() {
        val result = LenientJsonParser.parse("```\n{\"key\": \"value\"}\n```") as? Map<*, *>
        assertEquals("value", result!!["key"])
    }

    @Test
    fun `tolerates trailing commas in object`() {
        val result = LenientJsonParser.parse("""{"a": 1, "b": 2,}""") as? Map<*, *>
        assertEquals(1, result!!["a"])
        assertEquals(2, result["b"])
    }

    @Test
    fun `tolerates trailing commas in array`() {
        val result = LenientJsonParser.parse("""["x", "y",]""") as? List<*>
        assertEquals(listOf("x", "y"), result)
    }

    @Test
    fun `extracts JSON from surrounding explanation text`() {
        val result = LenientJsonParser.parse("""Here is the result: {"key": "val"} Done.""") as? Map<*, *>
        assertEquals("val", result!!["key"])
    }

    @Test
    fun `handles escaped quotes in strings`() {
        val result = LenientJsonParser.parse("""{"msg": "say \"hello\""}""") as? Map<*, *>
        assertEquals("""say "hello"""", result!!["msg"])
    }

    @Test
    fun `handles escape sequences`() {
        val result = LenientJsonParser.parse("""{"line": "one\ntwo"}""") as? Map<*, *>
        assertEquals("one\ntwo", result!!["line"])
    }

    @Test
    fun `returns null for plain text with no JSON structure`() {
        assertNull(LenientJsonParser.parse("this is not json at all"))
    }

    @Test
    fun `handles empty object`() {
        val result = LenientJsonParser.parse("{}") as? Map<*, *>
        assertNotNull(result)
        assertTrue(result!!.isEmpty())
    }

    @Test
    fun `handles empty array`() {
        val result = LenientJsonParser.parse("[]") as? List<*>
        assertNotNull(result)
        assertTrue(result!!.isEmpty())
    }

    @Test
    fun `parses array of objects`() {
        val result = LenientJsonParser.parse("""[{"id": 1}, {"id": 2}]""") as? List<*>
        assertNotNull(result)
        assertEquals(2, result!!.size)
        assertEquals(1, (result[0] as Map<*, *>)["id"])
        assertEquals(2, (result[1] as Map<*, *>)["id"])
    }

    @Test
    fun `large integer stays as Long`() {
        val result = LenientJsonParser.parse("""{"n": 9999999999}""") as? Map<*, *>
        assertEquals(9999999999L, result!!["n"])
    }
}
