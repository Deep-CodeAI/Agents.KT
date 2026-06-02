package agents_engine.mcp

/** A tool exposed by an MCP server via `tools/list` (#1734). */
data class McpToolInfo(
    val name: String,
    val title: String? = null,
    val description: String? = null,
    /** JSON Schema describing the tool's argument shape. */
    val inputSchema: Map<String, Any?>,
    /** Optional JSON Schema describing the tool's structured result shape. */
    val outputSchema: Map<String, Any?>? = null,
    val annotations: McpToolAnnotations? = null,
)
