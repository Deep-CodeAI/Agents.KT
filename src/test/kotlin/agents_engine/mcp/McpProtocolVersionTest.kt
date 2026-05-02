package agents_engine.mcp

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class McpProtocolVersionTest {

    private val toClose = mutableListOf<AutoCloseable>()
    private val toStop = mutableListOf<() -> Unit>()

    @AfterTest fun cleanup() {
        toClose.forEach { runCatching { it.close() } }
        toStop.forEach { runCatching { it() } }
    }

    @Test
    fun `default protocol version is the shared constant`() {
        assertEquals("2025-03-26", MCP_PROTOCOL_VERSION)
    }

    @Test
    fun `client surfaces server's protocolVersion and serverInfo over HTTP`() {
        val s = MockMcpServer.start {
            tool("noop") { }
        }.also { toStop.add { it.stop() } }

        val client = McpClient.connect(s.url).also { toClose.add(it) }

        assertEquals(MCP_PROTOCOL_VERSION, client.serverProtocolVersion)
        assertEquals("mock-mcp", client.serverName)
        assertEquals("0.0.1", client.serverVersion)
    }

    @Test
    fun `mock server can override protocolVersion and serverInfo for testing version drift`() {
        val s = MockMcpServer.start {
            protocolVersion = "2024-11-05"
            serverName = "future-mcp"
            serverVersion = "9.9.9"
            tool("noop") { }
        }.also { toStop.add { it.stop() } }

        val client = McpClient.connect(s.url).also { toClose.add(it) }

        assertEquals("2024-11-05", client.serverProtocolVersion)
        assertEquals("future-mcp", client.serverName)
        assertEquals("9.9.9", client.serverVersion)
    }

    @Test
    fun `protocolVersion round-trips over TCP`() {
        val s = MockTcpMcpServer.start {
            protocolVersion = "tcp-version-1"
            tool("noop") { }
        }.also { toStop.add { it.stop() } }

        val client = McpClient.connectTcp("127.0.0.1", s.port).also { toClose.add(it) }

        assertNotNull(client.serverProtocolVersion)
        assertEquals("tcp-version-1", client.serverProtocolVersion)
    }

    @Test
    fun `protocolVersion round-trips over stdio`() {
        val s = MockStdioMcpServer.start {
            protocolVersion = "stdio-version-1"
            tool("noop") { }
        }.also { toStop.add { it.stop() } }

        val client = s.connectClient().also { toClose.add(it) }

        assertEquals("stdio-version-1", client.serverProtocolVersion)
    }
}
