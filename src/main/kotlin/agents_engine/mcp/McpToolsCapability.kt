package agents_engine.mcp

/** The `tools` capability an MCP server reports (#1734). */
data class McpToolsCapability(
    /** Server emits `notifications/tools/list_changed` when its tool list mutates. */
    val listChanged: Boolean = false,
)
