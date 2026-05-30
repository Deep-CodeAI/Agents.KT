package agents_engine.mcp

import agents_engine.generation.LenientJsonParser

/**
 * `agents_engine/mcp/JsonRpc.kt` — single source of truth for the
 * JSON-RPC 2.0 wire shape shared by [McpClient] (request builder +
 * response parser) and [McpServer] / [McpStdioServer] (request
 * dispatcher + result/error envelope builders).
 *
 * Pre-#2796 the envelope construction was hand-rolled separately on
 * each side: the literal `"2.0"`, the notification-skip predicate
 * (`!containsKey("id") || method.startsWith("notifications/")`), the
 * error codes (`-32700/-32600/-32601/-32602/-32603`), and the wire
 * keys (`jsonrpc`/`method`/`params`/`id`/`result`/`error`) were
 * duplicated bare literals on both sides. This file centralises all
 * three categories so a future protocol version bump or wire-key
 * rename is one edit, and so any test that wants to assert "the
 * server returned a Method Not Found" can reference [JsonRpcErrorCode]
 * instead of `-32601`.
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

/**
 * Standard JSON-RPC 2.0 error codes from the spec, plus the project's
 * extension codes when those don't fit. Cheaper to read
 * `JsonRpcErrorCode.METHOD_NOT_FOUND` than `-32601` everywhere.
 */
internal object JsonRpcErrorCode {
    const val PARSE_ERROR: Int = -32700
    const val INVALID_REQUEST: Int = -32600
    const val METHOD_NOT_FOUND: Int = -32601
    const val INVALID_PARAMS: Int = -32602
    const val INTERNAL_ERROR: Int = -32603
}

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

/**
 * Small exception hierarchy for the MCP layer (#2796 acceptance
 * criterion 3). Previously `error()` strings were thrown from both
 * client and server, with the type information lost; callers had to
 * grep messages to distinguish transport problems from protocol
 * problems from tool-call failures. The hierarchy keeps the
 * `RuntimeException` cost (no checked-exception fan-out) while
 * letting consumers `catch` the category they care about.
 */
// Backed by IllegalStateException so existing `catch (e: IllegalStateException)`
// call sites — and the corresponding test assertions — keep working. The new
// type-discriminated subclasses give callers that care about the failure
// category a richer catch surface than message-grepping.
internal sealed class McpException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause) {
    /** Transport layer failure (network, malformed HTTP, stdio EOF). */
    class Transport(message: String, cause: Throwable? = null) : McpException(message, cause)

    /** Protocol-level failure (server returned a JSON-RPC `error`, malformed envelope, missing fields). */
    class Protocol(message: String, val code: Int? = null, cause: Throwable? = null) : McpException(message, cause)

    /** Tool-level failure (tool ran but returned isError or threw). */
    class ToolFailure(message: String, cause: Throwable? = null) : McpException(message, cause)
}
