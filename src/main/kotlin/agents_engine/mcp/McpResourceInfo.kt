package agents_engine.mcp

/** A resource exposed by an MCP server via `resources/list` (#1734). */
data class McpResourceInfo(
    val uri: String,
    val name: String,
    val title: String? = null,
    val description: String? = null,
    val mimeType: String? = null,
    /** Size in bytes when known. */
    val size: Long? = null,
    val annotations: McpResourceAnnotations? = null,
)
