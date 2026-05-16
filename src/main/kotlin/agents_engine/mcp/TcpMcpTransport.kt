package agents_engine.mcp

import java.net.Socket

/**
 * `agents_engine/mcp/TcpMcpTransport.kt` — line-delimited transport
 * over a TCP socket. Thin subclass of [LineDelimitedMcpTransport]
 * that delegates I/O to the socket's streams and closes the socket
 * in `close()`. See
 * `src/main/resources/internals-agent/mcp/TcpMcpTransport.md`
 * (#1837 / #1888).
 */
internal class TcpMcpTransport(private val socket: Socket) :
    LineDelimitedMcpTransport(socket.getInputStream(), socket.getOutputStream()) {

    override fun close() {
        super.close()
        runCatching { socket.close() }
    }
}
