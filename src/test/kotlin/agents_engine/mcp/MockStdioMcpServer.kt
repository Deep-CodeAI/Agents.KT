package agents_engine.mcp

import java.io.PipedInputStream
import java.io.PipedOutputStream

/**
 * In-process stdio MCP mock for tests. Wires two pipe pairs between a serving thread
 * and the client side, then `connectClient()` builds a real `McpClient` whose
 * `StdioMcpTransport` is plugged into those pipes.
 *
 * No subprocess required — exercises the full line-delimited protocol path.
 */
class MockStdioMcpServer internal constructor(private val protocol: MockMcpProtocol) {

    // Client → server.
    private val clientWritesTo = PipedOutputStream()
    private val serverReadsFrom = PipedInputStream(clientWritesTo, BUFFER)

    // Server → client.
    private val serverWritesTo = PipedOutputStream()
    private val clientReadsFrom = PipedInputStream(serverWritesTo, BUFFER)

    @Volatile private var stopped = false

    private val serverThread = Thread({ serveLoop() }, "MockStdioMcpServer").apply {
        isDaemon = true
        start()
    }

    /** Returns a fully-connected [McpClient] (handshake + tools/list complete). */
    fun connectClient(): McpClient =
        McpClient.connectStreams(input = clientReadsFrom, output = clientWritesTo)

    fun stop() {
        stopped = true
        runCatching { clientWritesTo.close() }
        runCatching { serverWritesTo.close() }
        serverThread.interrupt()
    }

    private fun serveLoop() {
        val reader = serverReadsFrom.bufferedReader(Charsets.UTF_8)
        val writer = serverWritesTo.bufferedWriter(Charsets.UTF_8)
        try {
            while (!stopped) {
                val line = reader.readLine() ?: return
                if (line.isBlank()) continue
                val response = try { protocol.process(line) }
                catch (e: Exception) {
                    """{"jsonrpc":"2.0","id":null,"error":{"code":-32603,"message":${McpJson.encode(e.message ?: e.toString())}}}"""
                }
                if (response != null) {
                    writer.write(response)
                    writer.write("\n")
                    writer.flush()
                }
            }
        } catch (_: java.io.IOException) {
            // pipe closed during shutdown — expected
        }
    }

    companion object {
        private const val BUFFER = 64 * 1024

        fun start(block: MockMcpServerBuilder.() -> Unit): MockStdioMcpServer =
            MockStdioMcpServer(MockMcpServerBuilder().apply(block).buildProtocol())
    }
}
