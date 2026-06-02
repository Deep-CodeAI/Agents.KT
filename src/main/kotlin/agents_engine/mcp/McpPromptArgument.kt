package agents_engine.mcp

/** A declared argument of an MCP prompt (#1734). */
data class McpPromptArgument(
    val name: String,
    val description: String? = null,
    val required: Boolean = false,
)
