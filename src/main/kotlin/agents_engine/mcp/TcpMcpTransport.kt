package agents_engine.mcp

import java.net.Socket

internal class TcpMcpTransport(private val socket: Socket) :
    LineDelimitedMcpTransport(socket.getInputStream(), socket.getOutputStream()) {

    override fun close() {
        super.close()
        runCatching { socket.close() }
    }
}
