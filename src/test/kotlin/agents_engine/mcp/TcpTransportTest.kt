package agents_engine.mcp

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class TcpTransportTest {

    private val servers = mutableListOf<MockTcpMcpServer>()
    private val clients = mutableListOf<McpClient>()

    @AfterTest fun cleanup() {
        clients.forEach { runCatching { it.close() } }
        servers.forEach { runCatching { it.stop() } }
    }

    private fun start(block: MockMcpServerBuilder.() -> Unit): MockTcpMcpServer =
        MockTcpMcpServer.start(block).also { servers.add(it) }

    private fun connect(server: MockTcpMcpServer): McpClient =
        McpClient.connectTcp("127.0.0.1", server.port).also { clients.add(it) }

    @Test
    fun `connectTcp performs handshake and lists tools`() {
        val s = start {
            tool("echo") { description = "echoes" }
            tool("greet") { description = "greets" }
        }
        val tools = connect(s).toolDefs()
        assertEquals(setOf("echo", "greet"), tools.map { it.name }.toSet())
    }

    @Test
    fun `tools call returns concatenated text content over TCP`() {
        val s = start {
            tool("greet") {
                respond { args -> listOf(textBlock("Hello, ${args["name"] ?: "world"}!")) }
            }
        }
        val result = connect(s).call("greet", mapOf("name" to "tcp"))
        assertEquals("Hello, tcp!", result)
    }

    @Test
    fun `jsonrpc error over TCP is surfaced as IllegalStateException`() {
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
    fun `Fibonacci MCP server works over TCP`() {
        val s = start { installFibonacciTool() }
        val client = connect(s)
        val seq = (1..6).map { client.call("fib_next", emptyMap()).toString().trim() }
        assertEquals(listOf("1", "1", "2", "3", "5", "8"), seq)
    }

    @Test
    fun `existing HTTP path still works after refactor`() {
        val httpServer = MockMcpServer.start {
            tool("ping") { respond { _ -> listOf(textBlock("pong")) } }
        }
        try {
            val client = McpClient.connect(httpServer.url)
            assertEquals("pong", client.call("ping", emptyMap()))
            client.close()
        } finally {
            httpServer.stop()
        }
    }
}
