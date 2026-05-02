package agents_engine.mcp

/**
 * Wire-level transport for an MCP client. Single-flight, synchronous.
 *
 * Implementations: [HttpMcpTransport] (Streamable HTTP), [TcpMcpTransport],
 * [StdioMcpTransport]. The JSON-RPC envelope is identical across all three;
 * only framing differs.
 */
internal interface McpTransport : AutoCloseable {
    /** Send a JSON-RPC request envelope, return the response envelope as a JSON string. */
    fun rpc(envelope: String): String

    /** Send a JSON-RPC notification envelope (no response is expected). */
    fun notify(envelope: String)

    override fun close()
}
