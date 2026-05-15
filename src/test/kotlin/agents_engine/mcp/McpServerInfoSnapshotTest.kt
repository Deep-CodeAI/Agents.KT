package agents_engine.mcp

import agents_engine.core.agent
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// #1734 — round-trip test for McpServerInfo, no transport stub needed.
//
// We use the framework's own services as fixtures: an agent → exposed via
// `McpServer.from(...)` → connected via `McpClient`. The wire is real
// (loopback HTTP). What McpClient surfaces in its snapshot must match
// what McpServer exposes, end-to-end. If a future change drifts either
// end of the wire — server reports a new capability shape, client parser
// misses a field — this test fires.

class McpServerInfoSnapshotTest {

    private var mcpServer: McpServer? = null
    private var mcpClient: McpClient? = null

    @AfterTest
    fun teardown() {
        mcpClient?.close()
        mcpServer?.stop()
    }

    @Test
    fun `snapshot reflects agent-as-MCP server identity, capabilities, and tool listing`() {
        // The agent has a single non-agentic skill — that's what McpServer
        // currently exposes (agentic skills need server-side LLM access,
        // out of scope for McpServer.from in the current slice).
        val greeter = agent<String, String>("greeter") {
            skills {
                skill<String, String>("greet", "Returns a greeting for the provided name") {
                    implementedBy { name -> "Hello, $name!" }
                }
            }
        }

        val server = McpServer.from(greeter) {
            port = 0  // auto-assign
            expose("greet")
        }.start().also { mcpServer = it }

        val client = McpClient.connect(server.url).also { mcpClient = it }

        val info = client.snapshot
        assertNotNull(info, "snapshot must be populated after handshake + loadTools")

        // ── Identity ───────────────────────────────────────────────
        assertEquals("agents-kt-mcp-server", info.name)
        assertEquals("0.1.3", info.version)
        assertEquals(MCP_PROTOCOL_VERSION, info.protocolVersion)
        // McpServer doesn't emit title or instructions today; assert their absence
        // so a future change that DOES emit them updates this test.
        assertNull(info.title, "McpServer doesn't emit serverInfo.title yet")
        assertNull(info.instructions, "McpServer doesn't emit initialize.instructions yet")

        // ── Capabilities ───────────────────────────────────────────
        // McpServer reports `{ tools: { listChanged: false } }` and nothing else.
        val caps = info.capabilities
        assertNotNull(caps.tools, "server declared tools capability")
        assertFalse(caps.tools.listChanged, "server doesn't push tool-list-changed notifications")
        assertNull(caps.resources, "server doesn't declare resources capability today")
        assertNull(caps.prompts, "server doesn't declare prompts capability today")
        assertFalse(caps.logging)
        assertFalse(caps.completions)
        assertTrue(caps.experimental.isEmpty())

        // ── Tools ──────────────────────────────────────────────────
        val tools = info.tools
        assertNotNull(tools, "tools listing populated when the server declares the capability")
        assertEquals(1, tools.size, "exactly one skill was exposed")
        val tool = tools.single()
        assertEquals("greet", tool.name)
        assertEquals("Returns a greeting for the provided name", tool.description)
        // String-input skill yields the canonical String input schema:
        //   { type: object, properties: { input: { type: string } }, required: [input] }
        assertEquals("object", tool.inputSchema["type"])
        @Suppress("UNCHECKED_CAST")
        val props = tool.inputSchema["properties"] as? Map<String, Any?>
        assertNotNull(props, "input schema should have properties: ${tool.inputSchema}")
        assertTrue("input" in props, "String-skill schema should expose `input` key: $props")
        // No annotations / outputSchema / title yet from McpServer.from — pin the absence.
        assertNull(tool.title)
        assertNull(tool.outputSchema)
        assertNull(tool.annotations)
    }
}
