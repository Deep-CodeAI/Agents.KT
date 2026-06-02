package agents_engine.mcp

/**
 * Standard JSON-RPC 2.0 error codes from the spec, plus the project's extension codes where the
 * standard ones don't fit (#2796). Reading `JsonRpcErrorCode.METHOD_NOT_FOUND` beats `-32601`.
 */
internal object JsonRpcErrorCode {
    const val PARSE_ERROR: Int = -32700
    const val INVALID_REQUEST: Int = -32600
    const val METHOD_NOT_FOUND: Int = -32601
    const val INVALID_PARAMS: Int = -32602
    const val INTERNAL_ERROR: Int = -32603
}
