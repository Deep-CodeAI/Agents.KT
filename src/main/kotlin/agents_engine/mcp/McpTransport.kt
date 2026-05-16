package agents_engine.mcp

/**
 * `agents_engine/mcp/McpTransport.kt` — internal interface for MCP
 * wire-level transports (single-flight, synchronous). Three
 * implementations: [HttpMcpTransport] (Streamable HTTP),
 * [TcpMcpTransport] (line-delimited over TCP socket), and
 * [StdioMcpTransport] (line-delimited over pipe). The JSON-RPC
 * envelope is identical across all three — only framing differs.
 * AutoCloseable. See
 * `src/main/resources/internals-agent/mcp/McpTransport.md`
 * (#1837 / #1886).
 */

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
