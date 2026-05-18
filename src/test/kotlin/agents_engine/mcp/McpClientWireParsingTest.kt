package agents_engine.mcp

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// Tests for #1973 — McpClient cluster (97 unkilled mutants, concentrated in
// listPrompts / listResources / getPrompt / readResource / loadTools wire-
// parsing). Drives McpClient directly with a fake transport that returns
// canned JSON-RPC response envelopes keyed by method.
//
// Avoids extending MockMcpServer (which is HTTP-bound and tools-only) —
// fake-transport is the lowest-friction seam for testing the wire-parsing
// branches.
class McpClientWireParsingTest {

    /**
     * Fake transport: matches request method (parsed from envelope), returns
     * the configured response body. Use [respondTo] to wire per-method canned
     * responses, [respondWith] for "always return this".
     */
    private class FakeTransport : McpTransport {
        private val byMethod = mutableMapOf<String, String>()
        private var fallback: String? = null

        fun respondTo(method: String, responseBody: String) {
            byMethod[method] = responseBody
        }

        fun respondWith(responseBody: String) {
            fallback = responseBody
        }

        override fun rpc(envelope: String): String {
            // Pull `method` out of the envelope text. Sloppy but fine for tests.
            val methodMatch = Regex("\"method\"\\s*:\\s*\"([^\"]+)\"").find(envelope)
            val method = methodMatch?.groupValues?.get(1) ?: ""
            val idMatch = Regex("\"id\"\\s*:\\s*(\\d+)").find(envelope)
            val id = idMatch?.groupValues?.get(1) ?: "1"
            val canned = byMethod[method] ?: fallback
                ?: error("FakeTransport: no canned response for method '$method'")
            // Substitute the id in the response so the envelope passes McpClient's check.
            return canned.replace("__ID__", id)
        }

        override fun notify(envelope: String) { /* no-op */ }
        override fun close() { /* no-op */ }
    }

    // Construct McpClient directly with the fake transport — bypasses the
    // companion factories' handshake()+loadTools() so we can test individual
    // wire-parsing branches in isolation. McpClient's listPrompts/etc.
    // public methods don't require a prior handshake; they post the RPC,
    // parse the result, return.
    private fun newClient(transport: FakeTransport): McpClient = McpClient(transport)

    private val toClose = mutableListOf<() -> Unit>()
    @AfterTest fun cleanup() { toClose.forEach { runCatching { it() } } }

    // ── listPrompts (24 unkilled) ─────────────────────────────────────────────

    @Test
    fun `listPrompts empty result returns empty list`() {
        val t = FakeTransport()
        t.respondTo("prompts/list", """{"jsonrpc":"2.0","id":__ID__,"result":{"prompts":[]}}""")
        val client = newClient(t); toClose.add { client.close() }
        assertEquals(emptyList(), client.listPrompts())
    }

    @Test
    fun `listPrompts result missing prompts field returns empty list`() {
        // Kills the `as? List<*> ?: return emptyList()` Elvis fallback mutant.
        val t = FakeTransport()
        t.respondTo("prompts/list", """{"jsonrpc":"2.0","id":__ID__,"result":{}}""")
        val client = newClient(t); toClose.add { client.close() }
        assertEquals(emptyList(), client.listPrompts())
    }

    @Test
    fun `listPrompts result not an object returns empty list`() {
        val t = FakeTransport()
        t.respondTo("prompts/list", """{"jsonrpc":"2.0","id":__ID__,"result":"not an object"}""")
        val client = newClient(t); toClose.add { client.close() }
        assertEquals(emptyList(), client.listPrompts())
    }

    @Test
    fun `listPrompts happy path parses name, description, arguments`() {
        val t = FakeTransport()
        t.respondTo("prompts/list", """{
            "jsonrpc":"2.0","id":__ID__,
            "result":{"prompts":[
                {
                    "name":"greet",
                    "description":"Say hi",
                    "arguments":[
                        {"name":"who","description":"recipient","required":true},
                        {"name":"tone"}
                    ]
                },
                {"name":"bare"}
            ]}
        }""")
        val client = newClient(t); toClose.add { client.close() }
        val prompts = client.listPrompts()
        assertEquals(2, prompts.size, "should parse both prompts")

        val greet = prompts[0]
        assertEquals("greet", greet.name)
        assertEquals("Say hi", greet.description)
        assertEquals(2, greet.arguments.size)
        assertEquals("who", greet.arguments[0].name)
        assertEquals("recipient", greet.arguments[0].description)
        assertEquals(true, greet.arguments[0].required)
        // `tone` arg without description and without required → required defaults to false.
        assertEquals("tone", greet.arguments[1].name)
        assertNull(greet.arguments[1].description)
        assertEquals(false, greet.arguments[1].required)

        // `bare` has no description, no arguments.
        val bare = prompts[1]
        assertEquals("bare", bare.name)
        assertNull(bare.description)
        assertEquals(emptyList(), bare.arguments)
    }

    @Test
    fun `listPrompts skips prompt entries missing name`() {
        // Kills the `name ?: return@mapNotNull null` mutant — entries without
        // a name MUST be skipped silently (server returning malformed data
        // shouldn't crash the client).
        val t = FakeTransport()
        t.respondTo("prompts/list", """{
            "jsonrpc":"2.0","id":__ID__,
            "result":{"prompts":[
                {"name":"good"},
                {"description":"no name field"},
                {"name":"alsoGood"}
            ]}
        }""")
        val client = newClient(t); toClose.add { client.close() }
        val prompts = client.listPrompts()
        assertEquals(listOf("good", "alsoGood"), prompts.map { it.name })
    }

    @Test
    fun `listPrompts skips argument entries missing name`() {
        val t = FakeTransport()
        t.respondTo("prompts/list", """{
            "jsonrpc":"2.0","id":__ID__,
            "result":{"prompts":[
                {"name":"p","arguments":[
                    {"name":"valid"},
                    {"description":"no name"}
                ]}
            ]}
        }""")
        val client = newClient(t); toClose.add { client.close() }
        val args = client.listPrompts().single().arguments
        assertEquals(listOf("valid"), args.map { it.name })
    }

    // ── getPrompt (11 unkilled) ───────────────────────────────────────────────

    @Test
    fun `getPrompt happy path joins text content from all messages`() {
        val t = FakeTransport()
        t.respondTo("prompts/get", """{
            "jsonrpc":"2.0","id":__ID__,
            "result":{
                "description":"...",
                "messages":[
                    {"role":"user","content":{"type":"text","text":"line one"}},
                    {"role":"user","content":{"type":"text","text":"line two"}}
                ]
            }
        }""")
        val client = newClient(t); toClose.add { client.close() }
        val rendered = client.getPrompt("anything", emptyMap())
        assertEquals("line one\nline two", rendered)
    }

    @Test
    fun `getPrompt empty messages returns empty string`() {
        val t = FakeTransport()
        t.respondTo("prompts/get", """{"jsonrpc":"2.0","id":__ID__,"result":{"messages":[]}}""")
        val client = newClient(t); toClose.add { client.close() }
        assertEquals("", client.getPrompt("x", emptyMap()))
    }

    @Test
    fun `getPrompt missing messages field returns empty string`() {
        val t = FakeTransport()
        t.respondTo("prompts/get", """{"jsonrpc":"2.0","id":__ID__,"result":{}}""")
        val client = newClient(t); toClose.add { client.close() }
        assertEquals("", client.getPrompt("x", emptyMap()))
    }

    @Test
    fun `getPrompt skips messages without text content block`() {
        val t = FakeTransport()
        t.respondTo("prompts/get", """{
            "jsonrpc":"2.0","id":__ID__,
            "result":{"messages":[
                {"role":"user","content":{"type":"text","text":"keep"}},
                {"role":"user","content":{"type":"image","data":"..."}},
                {"role":"user"}
            ]}
        }""")
        val client = newClient(t); toClose.add { client.close() }
        // Only the text block remains.
        assertEquals("keep", client.getPrompt("x", emptyMap()))
    }

    @Test
    fun `getPrompt result not an object throws`() {
        // The `as? Map<*, *> ?: error(...)` branch.
        val t = FakeTransport()
        t.respondTo("prompts/get", """{"jsonrpc":"2.0","id":__ID__,"result":"oops"}""")
        val client = newClient(t); toClose.add { client.close() }
        val ex = assertFails { client.getPrompt("x", emptyMap()) }
        assertTrue((ex.message ?: "").contains("non-object"))
    }

    // ── listResources (19 unkilled) ───────────────────────────────────────────

    @Test
    fun `listResources happy path parses all fields including optional size`() {
        val t = FakeTransport()
        t.respondTo("resources/list", """{
            "jsonrpc":"2.0","id":__ID__,
            "result":{"resources":[
                {
                    "uri":"file:///a.txt",
                    "name":"A",
                    "title":"Resource A",
                    "description":"first",
                    "mimeType":"text/plain",
                    "size":42
                },
                {"uri":"file:///b.txt","name":"B"}
            ]}
        }""")
        val client = newClient(t); toClose.add { client.close() }
        val resources = client.listResources()
        assertEquals(2, resources.size)

        val a = resources[0]
        assertEquals("file:///a.txt", a.uri)
        assertEquals("A", a.name)
        assertEquals("Resource A", a.title)
        assertEquals("first", a.description)
        assertEquals("text/plain", a.mimeType)
        assertEquals(42L, a.size)

        // Bare resource: only uri + name; everything else null.
        val b = resources[1]
        assertEquals("file:///b.txt", b.uri)
        assertEquals("B", b.name)
        assertNull(b.title)
        assertNull(b.description)
        assertNull(b.mimeType)
        assertNull(b.size)
    }

    @Test
    fun `listResources skips entries missing uri or name`() {
        val t = FakeTransport()
        t.respondTo("resources/list", """{
            "jsonrpc":"2.0","id":__ID__,
            "result":{"resources":[
                {"uri":"file:///ok","name":"OK"},
                {"name":"no uri"},
                {"uri":"file:///also-skip"},
                {"uri":"file:///good2","name":"G2"}
            ]}
        }""")
        val client = newClient(t); toClose.add { client.close() }
        val names = client.listResources().map { it.name }
        assertEquals(listOf("OK", "G2"), names)
    }

    @Test
    fun `listResources empty returns empty list`() {
        val t = FakeTransport()
        t.respondTo("resources/list", """{"jsonrpc":"2.0","id":__ID__,"result":{"resources":[]}}""")
        val client = newClient(t); toClose.add { client.close() }
        assertEquals(emptyList(), client.listResources())
    }

    @Test
    fun `listResources missing resources field returns empty list`() {
        val t = FakeTransport()
        t.respondTo("resources/list", """{"jsonrpc":"2.0","id":__ID__,"result":{}}""")
        val client = newClient(t); toClose.add { client.close() }
        assertEquals(emptyList(), client.listResources())
    }

    // ── readResource (9 unkilled) ─────────────────────────────────────────────

    @Test
    fun `readResource happy path joins text content blocks`() {
        val t = FakeTransport()
        t.respondTo("resources/read", """{
            "jsonrpc":"2.0","id":__ID__,
            "result":{"contents":[
                {"uri":"x","text":"first chunk"},
                {"uri":"x","text":"second chunk"}
            ]}
        }""")
        val client = newClient(t); toClose.add { client.close() }
        assertEquals("first chunk\nsecond chunk", client.readResource("file:///x"))
    }

    @Test
    fun `readResource skips non-text content blocks (binary)`() {
        val t = FakeTransport()
        t.respondTo("resources/read", """{
            "jsonrpc":"2.0","id":__ID__,
            "result":{"contents":[
                {"uri":"x","text":"text"},
                {"uri":"x","blob":"base64=="}
            ]}
        }""")
        val client = newClient(t); toClose.add { client.close() }
        assertEquals("text", client.readResource("file:///x"))
    }

    @Test
    fun `readResource empty contents returns empty string`() {
        val t = FakeTransport()
        t.respondTo("resources/read", """{"jsonrpc":"2.0","id":__ID__,"result":{"contents":[]}}""")
        val client = newClient(t); toClose.add { client.close() }
        assertEquals("", client.readResource("file:///x"))
    }

    @Test
    fun `readResource result not an object throws`() {
        val t = FakeTransport()
        t.respondTo("resources/read", """{"jsonrpc":"2.0","id":__ID__,"result":"oops"}""")
        val client = newClient(t); toClose.add { client.close() }
        val ex = assertFails { client.readResource("file:///x") }
        assertTrue((ex.message ?: "").contains("non-object"))
    }

    // ── Skill-factory delegations (kill toolSkills/promptSkills/resourceSkills
    // ── lambda-return-with-null mutants by actually invoking the factories) ──

    @Test
    fun `promptSkills returns one Skill per prompt with the right name`() {
        val t = FakeTransport()
        t.respondTo("prompts/list", """{
            "jsonrpc":"2.0","id":__ID__,
            "result":{"prompts":[
                {"name":"a","description":"first"},
                {"name":"b","description":"second"}
            ]}
        }""")
        val client = newClient(t); toClose.add { client.close() }
        val skills = client.promptSkills()
        assertEquals(listOf("a", "b"), skills.map { it.name })
        assertEquals("first", skills[0].description)
        assertEquals("second", skills[1].description)
    }

    @Test
    fun `promptSkills with prefix prepends to each skill name`() {
        val t = FakeTransport()
        t.respondTo("prompts/list", """{
            "jsonrpc":"2.0","id":__ID__,
            "result":{"prompts":[{"name":"hello"}]}
        }""")
        val client = newClient(t); toClose.add { client.close() }
        val skills = client.promptSkills(prefix = "myserver")
        assertEquals(listOf("myserver.hello"), skills.map { it.name })
    }

    @Test
    fun `resourceSkills returns one Skill per resource`() {
        val t = FakeTransport()
        t.respondTo("resources/list", """{
            "jsonrpc":"2.0","id":__ID__,
            "result":{"resources":[
                {"uri":"u1","name":"r1"},
                {"uri":"u2","name":"r2","description":"second"}
            ]}
        }""")
        val client = newClient(t); toClose.add { client.close() }
        val skills = client.resourceSkills()
        assertEquals(2, skills.size)
        assertEquals(listOf("r1", "r2"), skills.map { it.name })
        // First uses default `MCP resource <uri>` since description is null.
        assertTrue(skills[0].description.contains("u1"))
        assertEquals("second", skills[1].description)
    }

    @Test
    fun `resourceSkills with prefix prepends to each skill name`() {
        val t = FakeTransport()
        t.respondTo("resources/list", """{
            "jsonrpc":"2.0","id":__ID__,
            "result":{"resources":[{"uri":"u","name":"r"}]}
        }""")
        val client = newClient(t); toClose.add { client.close() }
        val skills = client.resourceSkills(prefix = "fs")
        assertEquals(listOf("fs.r"), skills.map { it.name })
    }
}
