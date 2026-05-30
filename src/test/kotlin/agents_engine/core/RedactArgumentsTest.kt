package agents_engine.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * #2490b — `redactArguments` helper. Pins:
 *
 * 1. Empty redaction set returns the input map unchanged (no allocation).
 * 2. Matching top-level field replaced with "[REDACTED]".
 * 3. Recurses into nested `Map<String, Any?>` — `headers: {Authorization: ...}` covered.
 * 4. Field-name match is case-sensitive (no surprise behaviour on different casing).
 * 5. Non-matching fields pass through unchanged.
 * 6. `null` values in matching fields ARE replaced (the field name match wins).
 * 7. Lists are NOT recursed into (per-element redaction is out of scope for v1).
 */
class RedactArgumentsTest {

    @Test
    fun `empty fields set returns input unchanged with no allocation`() {
        val args = mapOf("user" to "alice", "apiKey" to "sk-123")
        val out = redactArguments(args, emptySet())
        assertSame(args, out, "no allocation on the empty-policy path")
    }

    @Test
    fun `matching top-level field replaced with REDACTED`() {
        val args = mapOf("user" to "alice", "apiKey" to "sk-123")
        val out = redactArguments(args, setOf("apiKey"))
        assertEquals(mapOf("user" to "alice", "apiKey" to "[REDACTED]"), out)
    }

    @Test
    fun `recurses into nested Map for headers-style shapes`() {
        val args: Map<String, Any?> = mapOf(
            "url" to "https://api.example.com/v1",
            "headers" to mapOf(
                "Authorization" to "Bearer xyz",
                "X-Request-Id" to "r-1",
            ),
        )
        val out = redactArguments(args, setOf("Authorization"))
        @Suppress("UNCHECKED_CAST")
        val redactedHeaders = out["headers"] as Map<String, Any?>
        assertEquals("[REDACTED]", redactedHeaders["Authorization"])
        assertEquals("r-1", redactedHeaders["X-Request-Id"])
        assertEquals("https://api.example.com/v1", out["url"], "non-matching siblings untouched")
    }

    @Test
    fun `field name match is case-sensitive`() {
        val args = mapOf("apiKey" to "sk-123", "ApiKey" to "ALT-456")
        val out = redactArguments(args, setOf("apiKey"))
        assertEquals("[REDACTED]", out["apiKey"])
        assertEquals("ALT-456", out["ApiKey"], "different casing not matched")
    }

    @Test
    fun `non-matching fields pass through unchanged`() {
        val args = mapOf("user" to "alice", "count" to 42, "active" to true)
        val out = redactArguments(args, setOf("doesNotExist"))
        assertEquals(args, out)
    }

    @Test
    fun `null values in matching fields are still replaced`() {
        val args: Map<String, Any?> = mapOf("apiKey" to null, "user" to "alice")
        val out = redactArguments(args, setOf("apiKey"))
        assertEquals("[REDACTED]", out["apiKey"], "name match wins over null value")
    }

    @Test
    fun `lists are not recursed (v1 scope)`() {
        val args: Map<String, Any?> = mapOf(
            "items" to listOf(
                mapOf("apiKey" to "sk-1"),
                mapOf("apiKey" to "sk-2"),
            ),
        )
        val out = redactArguments(args, setOf("apiKey"))
        // List values pass through unchanged — inner maps NOT redacted in v1.
        @Suppress("UNCHECKED_CAST")
        val items = out["items"] as List<Map<String, Any?>>
        assertEquals("sk-1", items[0]["apiKey"], "list-element maps not recursed in v1")
        assertEquals("sk-2", items[1]["apiKey"])
    }

    @Test
    fun `multiple fields in one set all redacted`() {
        val args = mapOf("apiKey" to "sk", "password" to "pw", "user" to "alice")
        val out = redactArguments(args, setOf("apiKey", "password"))
        assertEquals("[REDACTED]", out["apiKey"])
        assertEquals("[REDACTED]", out["password"])
        assertEquals("alice", out["user"])
    }
}
