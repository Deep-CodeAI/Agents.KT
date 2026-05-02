package agents_engine.mcp

import agents_engine.generation.LenientJsonParser

/**
 * Transport-agnostic mock MCP server logic.
 *
 * Takes a JSON-RPC envelope (request or notification) and returns the response envelope,
 * or `null` if the input was a notification (no response expected).
 *
 * Used by [MockMcpServer] (HTTP), [MockTcpMcpServer] (TCP), and [MockStdioMcpServer] (stdio).
 */
internal class MockMcpProtocol(
    private val tools: Map<String, MockTool>,
    private val errorByMethod: Map<String, JsonRpcError>,
    val sessionId: String?,
    val protocolVersion: String,
    val serverName: String,
    val serverVersion: String,
) {
    fun process(envelopeText: String): String? {
        val request = LenientJsonParser.parse(envelopeText) as? Map<*, *> ?: return null
        val method = request["method"] as? String ?: return null
        val id = request["id"]

        // Notifications: no id, no response expected.
        if (method.startsWith("notifications/")) return null

        return when (method) {
            "initialize" -> jsonRpcResult(id, mapOf(
                "protocolVersion" to protocolVersion,
                "capabilities" to mapOf("tools" to emptyMap<String, Any?>()),
                "serverInfo" to mapOf("name" to serverName, "version" to serverVersion),
            ))
            "tools/list" -> {
                errorByMethod[method]?.let { return jsonRpcError(id, it) }
                val toolsArr = tools.values.map {
                    buildMap<String, Any?> {
                        put("name", it.name)
                        put("description", it.description)
                        it.inputSchema?.let { schema ->
                            put("inputSchema", LenientJsonParser.parse(schema) ?: emptyMap<String, Any?>())
                        }
                    }
                }
                jsonRpcResult(id, mapOf("tools" to toolsArr))
            }
            "tools/call" -> {
                errorByMethod[method]?.let { return jsonRpcError(id, it) }
                val params = request["params"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
                val name = params["name"] as? String
                    ?: return jsonRpcError(id, JsonRpcError(-32602, "Missing tool name"))
                val tool = tools[name]
                    ?: return jsonRpcError(id, JsonRpcError(-32601, "Unknown tool: $name"))
                @Suppress("UNCHECKED_CAST")
                val args = (params["arguments"] as? Map<String, Any?>) ?: emptyMap()
                val result = tool.invoke(args)
                jsonRpcResult(id, mapOf(
                    "content" to result.content,
                    "isError" to result.isError,
                ))
            }
            else -> jsonRpcError(id, JsonRpcError(-32601, "Method not found: $method"))
        }
    }

    private fun jsonRpcResult(id: Any?, result: Any?): String =
        """{"jsonrpc":"2.0","id":${McpJson.encode(id)},"result":${McpJson.encode(result)}}"""

    private fun jsonRpcError(id: Any?, error: JsonRpcError): String =
        """{"jsonrpc":"2.0","id":${McpJson.encode(id)},"error":${McpJson.encode(mapOf(
            "code" to error.code,
            "message" to error.message,
        ))}}"""
}
