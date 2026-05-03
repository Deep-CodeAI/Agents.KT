package agents_engine.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Tests for #857 — McpAuth.Bearer.toString() must NOT leak the token.
class McpAuthRedactionTest {

    @Test
    fun `Bearer toString does not contain the token value`() {
        val secret = "sk-very-secret-token-do-not-leak"
        val auth = McpAuth.Bearer(secret)
        val rendered = auth.toString()
        assertFalse(rendered.contains(secret), "toString must not include the raw token; got: '$rendered'")
        assertTrue(rendered.contains("redacted", ignoreCase = true), "toString must indicate redaction; got: '$rendered'")
    }

    @Test
    fun `Bearer toString does not leak via interpolation`() {
        val secret = "sk-another-secret-token"
        val auth = McpAuth.Bearer(secret)
        val s = "$auth"
        assertFalse(s.contains(secret), "string interpolation must not leak token; got: '$s'")
    }

    @Test
    fun `Bearer equals and hashCode still work (data-class semantics preserved)`() {
        val a = McpAuth.Bearer("same")
        val b = McpAuth.Bearer("same")
        val c = McpAuth.Bearer("different")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertFalse(a == c, "different tokens must not be equal")
    }

    @Test
    fun `Bearer token property still returns the actual value (programmatic access preserved)`() {
        val secret = "sk-programmatic"
        val auth = McpAuth.Bearer(secret)
        assertEquals(secret, auth.token, "the .token property must still expose the value for HTTP-header building")
    }
}
