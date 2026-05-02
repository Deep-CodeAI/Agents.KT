package agents_engine.mcp

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class StdioTransportTest {

    private val servers = mutableListOf<MockStdioMcpServer>()
    private val clients = mutableListOf<McpClient>()

    @AfterTest fun cleanup() {
        clients.forEach { runCatching { it.close() } }
        servers.forEach { runCatching { it.stop() } }
    }

    private fun start(block: MockMcpServerBuilder.() -> Unit): MockStdioMcpServer =
        MockStdioMcpServer.start(block).also { servers.add(it) }

    private fun connect(server: MockStdioMcpServer): McpClient =
        server.connectClient().also { clients.add(it) }

    @Test
    fun `connectStreams performs handshake and lists tools`() {
        val s = start {
            tool("echo") { description = "echoes" }
            tool("greet") { description = "greets" }
        }
        val tools = connect(s).toolDefs()
        assertEquals(setOf("echo", "greet"), tools.map { it.name }.toSet())
    }

    @Test
    fun `tools call returns text content over stdio`() {
        val s = start {
            tool("greet") { respond { args -> listOf(textBlock("Hello, ${args["name"] ?: "world"}!")) } }
        }
        assertEquals("Hello, stdio!", connect(s).call("greet", mapOf("name" to "stdio")))
    }

    @Test
    fun `jsonrpc error over stdio surfaces as IllegalStateException`() {
        val s = start {
            tool("bad") { /* declared */ }
            jsonRpcError(forMethod = "tools/call", code = -32603, message = "Internal error")
        }
        val client = connect(s)
        try {
            client.call("bad", emptyMap())
            fail("expected error")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("Internal error"), "got: ${e.message}")
        }
    }

    @Test
    fun `Fibonacci MCP server works over stdio`() {
        val s = start { installFibonacciTool() }
        val client = connect(s)
        val seq = (1..6).map { client.call("fib_next", emptyMap()).toString().trim() }
        assertEquals(listOf("1", "1", "2", "3", "5", "8"), seq)
    }
}
