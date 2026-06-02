package agents_engine.mcp

/** Internal description of an MCP tool as fetched by [McpClient] from `tools/list`. */
internal data class McpToolDescriptor(
    val name: String,
    val description: String,
    val inputSchema: Map<*, *>?,
    val title: String? = null,
    val outputSchema: Map<*, *>? = null,
    val annotations: McpToolAnnotations? = null,
)
