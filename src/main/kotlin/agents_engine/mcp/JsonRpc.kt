package agents_engine.mcp

import agents_engine.generation.LenientJsonParser

/**
 * `agents_engine/mcp/JsonRpc.kt` — helper for building and parsing JSON-RPC 2.0 envelopes, the
 * single source of truth shared by [McpClient] (request builder + response parser) and
 * [McpServer] / `McpStdioServer` (request dispatcher + result/error envelope builders). The wire
 * constants live in [JsonRpcWire], the spec error codes in [JsonRpcErrorCode], and the MCP-layer
 * exception hierarchy in [McpException] (split out per #3199).
 */

/**
 * Helper for building and parsing JSON-RPC 2.0 envelopes. All builders
 * route the value-encoding through [McpJson.encode] so the wire bytes
 * match what the rest of the MCP layer emits.
 */
internal object JsonRpc {

    /** Build a request/notification envelope. Notifications are signalled by passing `null` for [id]. */
    fun encodeRequest(id: Any?, method: String, params: Any?): String = buildString {
        append("""{"${JsonRpcWire.KEY_JSONRPC}":"${JsonRpcWire.VERSION}"""")
        if (id != null) {
            append(""","${JsonRpcWire.KEY_ID}":""")
            append(McpJson.encode(id))
        }
        append(""","${JsonRpcWire.KEY_METHOD}":""")
        append(McpJson.encode(method))
        if (params != null) {
            append(""","${JsonRpcWire.KEY_PARAMS}":""")
            append(McpJson.encode(params))
        }
        append("}")
    }

    /** Build a successful response envelope. */
    fun encodeResult(id: Any?, result: Any?): String =
        """{"${JsonRpcWire.KEY_JSONRPC}":"${JsonRpcWire.VERSION}","${JsonRpcWire.KEY_ID}":${McpJson.encode(id)},"${JsonRpcWire.KEY_RESULT}":${McpJson.encode(result)}}"""

    /** Build a failure response envelope. */
    fun encodeError(id: Any?, code: Int, message: String): String {
        val errorObj = mapOf(JsonRpcWire.KEY_CODE to code, JsonRpcWire.KEY_MESSAGE to message)
        return """{"${JsonRpcWire.KEY_JSONRPC}":"${JsonRpcWire.VERSION}","${JsonRpcWire.KEY_ID}":${McpJson.encode(id)},"${JsonRpcWire.KEY_ERROR}":${McpJson.encode(errorObj)}}"""
    }

    /** Parse a payload as a JSON-RPC envelope object. Returns null when the payload isn't a JSON object. */
    @Suppress("UNCHECKED_CAST")
    fun parseEnvelope(payload: String): Map<String, Any?>? =
        LenientJsonParser.parse(payload) as? Map<String, Any?>

    /**
     * Returns true when the JSON-RPC payload should be skipped without a response:
     * notifications (no `id` field, or `method` prefixed with `notifications/`).
     */
    fun isNotification(envelope: Map<*, *>): Boolean {
        if (!envelope.containsKey(JsonRpcWire.KEY_ID)) return true
        val method = envelope[JsonRpcWire.KEY_METHOD] as? String
        return method != null && method.startsWith(JsonRpcWire.NOTIFICATION_PREFIX)
    }
}
