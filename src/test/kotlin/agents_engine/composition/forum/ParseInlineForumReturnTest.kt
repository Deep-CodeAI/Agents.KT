package agents_engine.composition.forum

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// #4514 — direct coverage of parseInlineForumReturn across the shapes a model might emit and
// the negatives it must NOT mistake for a return.

class ParseInlineForumReturnTest {

    @Test fun `name shape with numeric value`() {
        val v = parseInlineForumReturn("""{"name":"forum_return","arguments":{"value":108}}""")?.value
        assertEquals(108, (v as Number).toInt())
    }

    @Test fun `tool shape with string value`() {
        assertEquals("hi", parseInlineForumReturn("""{"tool":"forum_return","arguments":{"value":"hi"}}""")?.value)
    }

    @Test fun `parameters key is accepted as well as arguments`() {
        assertEquals("p", parseInlineForumReturn("""{"name":"forum_return","parameters":{"value":"p"}}""")?.value)
    }

    @Test fun `single non-value argument is used directly`() {
        assertEquals("blue", parseInlineForumReturn("""{"name":"forum_return","arguments":{"answer":"blue"}}""")?.value)
    }

    @Test fun `multiple arguments without a value key return the whole map`() {
        val v = parseInlineForumReturn("""{"name":"forum_return","arguments":{"a":1,"b":2}}""")?.value
        assertTrue(v is Map<*, *> && v.size == 2, "got: $v")
    }

    @Test fun `empty arguments yield empty string`() {
        assertEquals("", parseInlineForumReturn("""{"name":"forum_return","arguments":{}}""")?.value)
    }

    @Test fun `null value is matched (distinct from no-match)`() {
        val match = parseInlineForumReturn("""{"name":"forum_return","arguments":{"value":null}}""")
        assertTrue(match != null, "must match")
        assertNull(match.value)
    }

    @Test fun `a different tool name is not a forum_return`() {
        assertNull(parseInlineForumReturn("""{"name":"some_tool","arguments":{"value":1}}"""))
    }

    @Test fun `plain text is not a forum_return`() {
        assertNull(parseInlineForumReturn("the answer is 42"))
    }

    @Test fun `forum_return without an arguments object is not matched`() {
        assertNull(parseInlineForumReturn("""{"name":"forum_return"}"""))
    }

    @Test fun `malformed json does not throw and does not match`() {
        assertNull(parseInlineForumReturn("""{"name":"forum_return", broken"""))
    }

    @Test fun `non-string verdict is not matched`() {
        assertNull(parseInlineForumReturn(42))
        assertNull(parseInlineForumReturn(null))
    }
}
