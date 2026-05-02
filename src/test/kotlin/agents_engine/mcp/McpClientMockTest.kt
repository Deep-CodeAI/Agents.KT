package agents_engine.mcp

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class McpClientMockTest {

    private var server: MockMcpServer? = null

    @AfterTest fun stop() { server?.stop() }

    private fun startServer(block: MockMcpServerBuilder.() -> Unit): MockMcpServer =
        MockMcpServer.start(block).also { server = it }

    @Test
    fun `connects and lists declared tools`() {
        val s = startServer {
            tool("greet") {
                description = "Greets a person"
                inputSchema = """{"type":"object","properties":{"name":{"type":"string"}},"required":["name"]}"""
            }
            tool("echo") { description = "Echoes input" }
        }

        val client = McpClient.connect(s.url)
        val tools = client.toolDefs()

        assertEquals(2, tools.size)
        assertEquals(setOf("greet", "echo"), tools.map { it.name }.toSet())
        assertTrue(
            tools.first { it.name == "greet" }.description.contains("Greets a person"),
            "tool description should be propagated to ToolDef",
        )
    }

    @Test
    fun `propagates input schema into ToolDef description for the LLM`() {
        val s = startServer {
            tool("create_pr") {
                description = "Creates a pull request"
                inputSchema = """{"type":"object","properties":{"title":{"type":"string"},"head":{"type":"string"}}}"""
            }
        }

        val toolDef = McpClient.connect(s.url).toolDefs().single()

        assertTrue(toolDef.description.contains("title"), "schema field 'title' should appear in description")
        assertTrue(toolDef.description.contains("head"), "schema field 'head' should appear in description")
    }

    @Test
    fun `tools call returns concatenated text content`() {
        val s = startServer {
            tool("greet") {
                respond { args ->
                    val name = args["name"] as? String ?: "world"
                    listOf(textBlock("Hello, $name!"))
                }
            }
        }

        val client = McpClient.connect(s.url)
        val result = client.call("greet", mapOf("name" to "Konstantin"))

        assertEquals("Hello, Konstantin!", result)
    }

    @Test
    fun `tools call concatenates multiple text blocks with newlines`() {
        val s = startServer {
            tool("multi") {
                respond { _ -> listOf(textBlock("line one"), textBlock("line two")) }
            }
        }

        val result = McpClient.connect(s.url).call("multi", emptyMap())

        assertEquals("line one\nline two", result)
    }

    @Test
    fun `tools call with isError true throws`() {
        val s = startServer {
            tool("breaks") {
                respondError("upstream service unreachable")
            }
        }

        val client = McpClient.connect(s.url)

        try {
            client.call("breaks", emptyMap())
            fail("expected error")
        } catch (e: IllegalStateException) {
            assertTrue(
                e.message!!.contains("upstream service unreachable"),
                "error message should contain server-provided text, got: ${e.message}",
            )
        }
    }

    @Test
    fun `jsonrpc error envelope on tools call throws`() {
        val s = startServer {
            tool("noop") { /* declared, but server will respond with JSON-RPC error */ }
            jsonRpcError(forMethod = "tools/call", code = -32603, message = "Internal error")
        }

        val client = McpClient.connect(s.url)

        try {
            client.call("noop", emptyMap())
            fail("expected error")
        } catch (e: IllegalStateException) {
            assertTrue(
                e.message!!.contains("Internal error"),
                "should surface JSON-RPC error.message, got: ${e.message}",
            )
        }
    }

    @Test
    fun `sse response variant is parsed`() {
        val s = startServer {
            tool("greet") { respond { _ -> listOf(textBlock("hi via sse")) } }
            useSseResponses = true
        }

        val result = McpClient.connect(s.url).call("greet", emptyMap())

        assertEquals("hi via sse", result)
    }

    @Test
    fun `mcp session id from initialize is sent on subsequent requests`() {
        val s = startServer {
            sessionId = "mock-sess-xyz"
            tool("ping") { respond { _ -> listOf(textBlock("pong")) } }
        }

        McpClient.connect(s.url).call("ping", emptyMap())

        val seen = s.sessionIdsReceived()
        assertTrue(
            seen.any { it == "mock-sess-xyz" },
            "expected client to send Mcp-Session-Id header on follow-up requests, saw: $seen",
        )
    }
}
