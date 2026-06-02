package agents_engine.mcp

/**
 * The JSON-RPC 2.0 wire constants (version + envelope keys) shared by [McpClient] and
 * [McpServer] / `McpStdioServer` (#2796). Centralised so a protocol-version bump or wire-key
 * rename is one edit instead of bare literals duplicated on both sides.
 */
internal object JsonRpcWire {
    const val VERSION: String = "2.0"
    const val KEY_JSONRPC: String = "jsonrpc"
    const val KEY_METHOD: String = "method"
    const val KEY_PARAMS: String = "params"
    const val KEY_ID: String = "id"
    const val KEY_RESULT: String = "result"
    const val KEY_ERROR: String = "error"
    const val KEY_CODE: String = "code"
    const val KEY_MESSAGE: String = "message"

    /** A method name starting with this prefix is a notification — no response is expected. */
    const val NOTIFICATION_PREFIX: String = "notifications/"
}
