package agents_engine.mcp

/** The `prompts` capability an MCP server reports (#1734). */
data class McpPromptsCapability(
    /** Server emits `notifications/prompts/list_changed`. */
    val listChanged: Boolean = false,
)
