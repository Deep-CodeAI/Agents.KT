package agents_engine.mcp

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * In-process TCP MCP mock. Binds a `ServerSocket` on an auto-assigned port,
 * accepts each incoming connection on a worker thread, and dispatches
 * line-delimited JSON-RPC envelopes through [MockMcpProtocol].
 *
 * Single connection per client; each `start()` call binds a fresh port.
 */
class MockTcpMcpServer internal constructor(
    private val server: ServerSocket,
    private val protocol: MockMcpProtocol,
) {
    val port: Int get() = server.localPort
    private val workers = Executors.newCachedThreadPool { r ->
        Thread(r, "MockTcpMcpServer-${server.localPort}").apply { isDaemon = true }
    }
    init {
        // Accept loop runs on its own daemon thread; we don't retain a handle (stop() closes the
        // ServerSocket, which unblocks accept()).
        Thread({ acceptLoop() }, "MockTcpMcpServer-accept-${server.localPort}").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        runCatching { server.close() }
        workers.shutdownNow()
    }

    private fun acceptLoop() {
        while (!server.isClosed) {
            val client = try { server.accept() } catch (_: Exception) { return }
            workers.submit { serve(client) }
        }
    }

    private fun serve(client: Socket) {
        client.use { sock ->
            val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
            val writer = BufferedWriter(OutputStreamWriter(sock.getOutputStream(), Charsets.UTF_8))
            while (!sock.isClosed) {
                val line = reader.readLine() ?: return
                if (line.isBlank()) continue
                val response = try {
                    protocol.process(line)
                } catch (e: Exception) {
                    """{"jsonrpc":"2.0","id":null,"error":{"code":-32603,"message":${McpJson.encode(e.message ?: e.toString())}}}"""
                }
                if (response != null) {
                    writer.write(response)
                    writer.write("\n")
                    writer.flush()
                }
            }
        }
    }

    companion object {
        fun start(block: MockMcpServerBuilder.() -> Unit): MockTcpMcpServer {
            val builder = MockMcpServerBuilder().apply(block)
            val socket = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
            return MockTcpMcpServer(socket, builder.buildProtocol())
        }
    }
}
