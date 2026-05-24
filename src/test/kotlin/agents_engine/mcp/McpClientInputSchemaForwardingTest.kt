package agents_engine.mcp

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #2377 — `McpClient.toolDefs()` must forward each tool's upstream
 * `inputSchema` to the resulting `ToolDef.parametersSchemaJson` so the
 * provider clients can emit a real `parameters` field instead of the
 * permissive empty-object fallback. Without this, the LLM saw the
 * schema embedded in the description prose while the wire `parameters`
 * announced "anything goes" — conflicting signal.
 */
class McpClientInputSchemaForwardingTest {

    private val toClose = mutableListOf<() -> Unit>()
    @AfterTest fun cleanup() { toClose.forEach { runCatching { it() } } }

    @Test
    fun `toolDefs carries inputSchema through to parametersSchemaJson`() {
        val schema = """{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}"""
        val server = MockStdioMcpServer.start {
            tool("search") {
                description = "Search the corpus."
                inputSchema = schema
                respond { args ->
                    listOf(textBlock("you searched for ${args["query"]}"))
                }
            }
        }
        toClose.add { server.stop() }

        val client = server.connectClient()
        toClose.add { client.close() }

        val defs = client.toolDefs()
        assertEquals(1, defs.size)
        val def = defs.single()
        assertEquals("search", def.name)
        val forwarded = def.parametersSchemaJson
        assertNotNull(forwarded, "parametersSchemaJson must be non-null when inputSchema is present")
        assertTrue(
            forwarded.contains(""""required":["query"]"""),
            "Forwarded schema missing 'required' clause: $forwarded",
        )
        assertTrue(
            forwarded.contains(""""query""""),
            "Forwarded schema missing property name: $forwarded",
        )
    }

    @Test
    fun `toolDefs leaves parametersSchemaJson null when inputSchema is absent`() {
        val server = MockStdioMcpServer.start {
            tool("ping") {
                description = "No-args ping."
                respond { _ -> listOf(textBlock("pong")) }
            }
        }
        toClose.add { server.stop() }

        val client = server.connectClient()
        toClose.add { client.close() }

        val def = client.toolDefs().single()
        assertNull(def.parametersSchemaJson, "Tool with no inputSchema should have null parametersSchemaJson")
    }

    @Test
    fun `prefixed toolDefs still carry the schema through`() {
        val server = MockStdioMcpServer.start {
            tool("fetch") {
                description = "HTTP GET."
                inputSchema = """{"type":"object","properties":{"url":{"type":"string"}}}"""
                respond { _ -> listOf(textBlock("ok")) }
            }
        }
        toClose.add { server.stop() }

        val client = server.connectClient()
        toClose.add { client.close() }

        val def = client.toolDefs(prefix = "web").single()
        assertEquals("web.fetch", def.name)
        val forwarded = def.parametersSchemaJson
        assertNotNull(forwarded)
        assertTrue(forwarded.contains(""""url""""))
    }
}
