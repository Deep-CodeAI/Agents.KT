package agents_engine.model

import agents_engine.generation.LenientJsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #2659 — OpenAI's automatic prefix caching uses a deployer-supplied
 * `prompt_cache_key` to group same-shape requests onto the same cache
 * shard. The Agents.KT agentic loop derives a stable key from the agent
 * identity (+ manifestHash prefix when available) and threads it into
 * the OpenAI request body when caching is enabled.
 *
 * Pins both halves:
 * - `prompt_cache_key` field appears in the wire body when set.
 * - Field is omitted when null (default, e.g. when caching is disabled).
 */
class OpenAiPromptCacheKeyTest {

    private class StubClient(
        promptCacheKey: String?,
        private val canned: String,
    ) : OpenAiClient(
        apiKey = "test-key",
        model = "gpt-4o-mini",
        temperature = 0.0,
        promptCacheKey = promptCacheKey,
    ) {
        val sentBodies: MutableList<String> = mutableListOf()
        override fun sendChat(body: String, headers: Map<String, String>): String {
            sentBodies.add(body)
            return canned
        }
    }

    @Test
    fun `prompt_cache_key field appears in the request body when configured`() {
        val client = StubClient(
            promptCacheKey = "agents-kt:my-agent:abcdef123456",
            canned = """{"choices":[{"message":{"content":"ok"}}]}""",
        )

        client.chat(listOf(LlmMessage("user", "hi")))

        val root = LenientJsonParser.parse(client.sentBodies.single()) as Map<*, *>
        assertEquals(
            "agents-kt:my-agent:abcdef123456",
            root["prompt_cache_key"],
            "OpenAI routing key must appear verbatim in the request body",
        )
    }

    @Test
    fun `prompt_cache_key field is omitted from the body when null`() {
        val client = StubClient(
            promptCacheKey = null,
            canned = """{"choices":[{"message":{"content":"ok"}}]}""",
        )

        client.chat(listOf(LlmMessage("user", "hi")))

        val root = LenientJsonParser.parse(client.sentBodies.single()) as Map<*, *>
        assertNull(root["prompt_cache_key"], "no key set → field must be absent (not empty string)")
    }

    @Test
    fun `prompt_cache_key is JSON-escaped (handles colons + slashes safely)`() {
        // Cache keys include ':' and might include other special chars in
        // deployer-customised paths. Verify the JSON encoder properly
        // quotes them (the value already includes colons in the default
        // shape `agents-kt:<agent>:<hash>`).
        val keyWithSpecials = """agents-kt:weird/agent:has"quote"""
        val client = StubClient(
            promptCacheKey = keyWithSpecials,
            canned = """{"choices":[{"message":{"content":"ok"}}]}""",
        )

        client.chat(listOf(LlmMessage("user", "hi")))

        val root = LenientJsonParser.parse(client.sentBodies.single()) as Map<*, *>
        assertEquals(
            keyWithSpecials,
            root["prompt_cache_key"],
            "special characters must round-trip through the JSON encoder unchanged",
        )
    }

    @Test
    fun `prompt_cache_key does not perturb other fields (regression safety)`() {
        // Adding a new field should not change the surrounding request
        // shape — pin the standard fields are still present.
        val client = StubClient(
            promptCacheKey = "agents-kt:test",
            canned = """{"choices":[{"message":{"content":"ok"}}]}""",
        )

        client.chat(listOf(LlmMessage("user", "ping")))

        val root = LenientJsonParser.parse(client.sentBodies.single()) as Map<*, *>
        assertEquals("gpt-4o-mini", root["model"])
        assertTrue(root.containsKey("temperature"))
        assertTrue(root.containsKey("max_tokens"))
        assertTrue(root.containsKey("messages"))
    }
}
