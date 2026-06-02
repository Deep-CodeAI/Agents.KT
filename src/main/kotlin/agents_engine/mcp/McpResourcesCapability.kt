package agents_engine.mcp

/** The `resources` capability an MCP server reports (#1734). */
data class McpResourcesCapability(
    /** Server emits `notifications/resources/list_changed`. */
    val listChanged: Boolean = false,
    /** Server supports `resources/subscribe` for per-resource update notifications. */
    val subscribe: Boolean = false,
)
