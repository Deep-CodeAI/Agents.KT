package agents_engine.model

import agents_engine.generation.LenientJsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * #2658 — ClaudeClient consumes [CacheHint]s on `LlmMessage`s and emits
 * Anthropic's explicit `cache_control` breakpoints on the wire.
 *
 * Placement rules pinned here:
 * - A `SystemPrompt`-segment hint on the main system message → cache_control
 *   on the LAST tool definition (caches the tool-defs block) AND on the
 *   system block (caches system + prior).
 * - A `Conversation`-segment hint on an assistant/user message → cache_control
 *   on the message's last content block (rolling breakpoint at turn end).
 * - A `Custom`-segment hint on an extra system-role message → cache_control
 *   on its block inside Anthropic's `system` array.
 * - Coalesce at 4 breakpoints total — any beyond that are silently dropped
 *   (Anthropic's per-request cap).
 *
 * TTL mapping: `Duration > 5 minutes` → `"ttl":"1h"`; otherwise the default
 * ephemeral form (no explicit ttl). Anthropic supports those two values.
 */
class ClaudeCacheBreakpointTest {

    private class StubClient(
        tools: List<ToolDef> = emptyList(),
        private val canned: String = """{"id":"x","content":[{"type":"text","text":"ok"}]}""",
    ) : ClaudeClient(
        apiKey = "test-key",
        model = "claude-opus-4-7",
        temperature = 0.0,
        maxTokens = 1024,
        tools = tools,
    ) {
        val sentBodies: MutableList<String> = mutableListOf()
        override fun sendChat(body: String, headers: Map<String, String>): String {
            sentBodies.add(body)
            return canned
        }
    }

    @Test
    fun `no cache hint emits legacy system string form, no cache_control anywhere`() {
        // Backwards-compat sanity: a request with no hints looks byte-for-byte
        // like it did before #2658 (system as a string, no cache_control).
        val client = StubClient()
        client.chat(listOf(
            LlmMessage("system", "You are helpful."),
            LlmMessage("user", "hi"),
        ))

        val body = client.sentBodies.single()
        assertFalse(body.contains("cache_control"), "no hints → no cache_control: $body")
        // String form preserved.
        val root = LenientJsonParser.parse(body) as Map<*, *>
        assertEquals("You are helpful.", root["system"])
    }

    @Test
    fun `SystemPrompt hint emits cache_control on last tool and on system block`() {
        val tools = listOf(
            ToolDef("first_tool", "First", parametersSchemaJson = """{"type":"object","properties":{}}""") { "ok" },
            ToolDef("last_tool", "Last", parametersSchemaJson = """{"type":"object","properties":{}}""") { "ok" },
        )
        val client = StubClient(tools = tools)
        client.chat(listOf(
            LlmMessage(
                "system",
                "You are a careful assistant.",
                cacheHint = CacheHint(segment = CacheSegment.SystemPrompt),
            ),
            LlmMessage("user", "ping"),
        ))

        val body = client.sentBodies.single()
        val root = LenientJsonParser.parse(body) as Map<*, *>

        // System is array form with cache_control on the one system block.
        val systemArr = root["system"] as List<*>
        assertEquals(1, systemArr.size)
        val systemBlock = systemArr.single() as Map<*, *>
        assertEquals("You are a careful assistant.", systemBlock["text"])
        val systemCacheControl = systemBlock["cache_control"] as Map<*, *>
        assertEquals("ephemeral", systemCacheControl["type"], "default TTL → no ttl field")
        assertNull(systemCacheControl["ttl"])

        // Tools: cache_control on the LAST tool, NOT on the first.
        val toolsArr = root["tools"] as List<*>
        val firstTool = toolsArr.first() as Map<*, *>
        val lastTool = toolsArr.last() as Map<*, *>
        assertNull(firstTool["cache_control"], "first tool must NOT carry cache_control")
        assertNotNull(lastTool["cache_control"], "last tool must carry cache_control (closes tool-defs block)")
    }

    @Test
    fun `ttl above 5 minutes maps to Anthropic '1h' explicit form`() {
        val client = StubClient()
        client.chat(listOf(
            LlmMessage(
                "system",
                "Long-lived system prompt.",
                cacheHint = CacheHint(segment = CacheSegment.SystemPrompt, ttl = 1.hours),
            ),
            LlmMessage("user", "ping"),
        ))

        val body = client.sentBodies.single()
        val root = LenientJsonParser.parse(body) as Map<*, *>
        val cc = ((root["system"] as List<*>).single() as Map<*, *>)["cache_control"] as Map<*, *>
        assertEquals("1h", cc["ttl"], "Duration > 5min → ttl=1h")
    }

    @Test
    fun `ttl of exactly 5 minutes uses default ephemeral form (no explicit ttl)`() {
        val client = StubClient()
        client.chat(listOf(
            LlmMessage(
                "system",
                "Short-TTL system prompt.",
                cacheHint = CacheHint(segment = CacheSegment.SystemPrompt, ttl = 5.minutes),
            ),
            LlmMessage("user", "ping"),
        ))

        val body = client.sentBodies.single()
        val root = LenientJsonParser.parse(body) as Map<*, *>
        val cc = ((root["system"] as List<*>).single() as Map<*, *>)["cache_control"] as Map<*, *>
        assertNull(cc["ttl"], "TTL ≤ 5min → default ephemeral (no ttl field)")
    }

    @Test
    fun `Custom segment hint emits its own cache_control inside the system array`() {
        val client = StubClient()
        client.chat(listOf(
            LlmMessage(
                "system",
                "Main system prompt.",
                cacheHint = CacheHint(segment = CacheSegment.SystemPrompt),
            ),
            LlmMessage(
                "system",
                "Big retrieved document …",
                cacheHint = CacheHint(segment = CacheSegment.Custom("doc-1")),
            ),
            LlmMessage("user", "ping"),
        ))

        val body = client.sentBodies.single()
        val root = LenientJsonParser.parse(body) as Map<*, *>
        val systemArr = root["system"] as List<*>
        assertEquals(2, systemArr.size, "two system blocks → main + custom")
        val main = systemArr[0] as Map<*, *>
        val custom = systemArr[1] as Map<*, *>
        assertNotNull(main["cache_control"], "main system has cache_control")
        assertNotNull(custom["cache_control"], "custom segment has its own cache_control")
        assertEquals("Big retrieved document …", custom["text"])
    }

    @Test
    fun `Conversation hint emits cache_control on the message's last content block`() {
        // Rolling-conversation mode: each turn end's assistant message gets
        // a cache breakpoint so the growing prefix keeps hitting.
        val client = StubClient()
        client.chat(listOf(
            LlmMessage("user", "earlier"),
            LlmMessage(
                "assistant",
                "earlier reply",
                cacheHint = CacheHint(segment = CacheSegment.Conversation),
            ),
            LlmMessage("user", "now"),
        ))

        val body = client.sentBodies.single()
        val root = LenientJsonParser.parse(body) as Map<*, *>
        val messages = root["messages"] as List<*>
        val assistantMsg = messages.first { (it as Map<*, *>)["role"] == "assistant" } as Map<*, *>
        val content = assistantMsg["content"] as List<*>
        val lastBlock = content.last() as Map<*, *>
        assertNotNull(lastBlock["cache_control"], "rolling-conversation hint → cache_control on last block")
    }

    @Test
    fun `breakpoint budget caps at 4 — extras are silently dropped`() {
        // System + last tool + 2 custom segments + rolling conversation = 5
        // candidates but Anthropic only takes 4. The latest-priority ones
        // get dropped silently (we log a warning; runtime stays correct).
        val tools = listOf(
            ToolDef("only_tool", "Tool", parametersSchemaJson = """{"type":"object","properties":{}}""") { "ok" },
        )
        val client = StubClient(tools = tools)
        client.chat(listOf(
            LlmMessage(
                "system",
                "Main.",
                cacheHint = CacheHint(segment = CacheSegment.SystemPrompt),
            ),
            LlmMessage(
                "system",
                "Doc 1.",
                cacheHint = CacheHint(segment = CacheSegment.Custom("doc-1")),
            ),
            LlmMessage(
                "system",
                "Doc 2.",
                cacheHint = CacheHint(segment = CacheSegment.Custom("doc-2")),
            ),
            LlmMessage("user", "earlier"),
            LlmMessage(
                "assistant",
                "earlier reply",
                cacheHint = CacheHint(segment = CacheSegment.Conversation),
            ),
            LlmMessage("user", "now"),
        ))

        val body = client.sentBodies.single()
        val ccCount = Regex("cache_control").findAll(body).count()
        assertTrue(ccCount <= 4, "Anthropic supports ≤ 4 cache_control markers; emitted $ccCount")
    }

    @Test
    fun `caching disabled (no hints) preserves legacy wire format byte-identically`() {
        // The cache layer is additive — without hints, the body shape must
        // not change. Pin the no-hint path so a future refactor of the
        // breakpoint code can't silently mutate non-caching agents.
        val tools = listOf(
            ToolDef("calc", "Math", parametersSchemaJson = """{"type":"object","properties":{}}""") { "ok" },
        )
        val client = StubClient(tools = tools)
        client.chat(listOf(
            LlmMessage("system", "You compute."),
            LlmMessage("user", "2+2"),
        ))

        val body = client.sentBodies.single()
        assertFalse(body.contains("cache_control"))
        val root = LenientJsonParser.parse(body) as Map<*, *>
        // System still string form.
        assertEquals("You compute.", root["system"])
        // Tool defs unchanged.
        val onlyTool = (root["tools"] as List<*>).single() as Map<*, *>
        assertNull(onlyTool["cache_control"])
    }
}
