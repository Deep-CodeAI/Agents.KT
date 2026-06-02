package agents_engine.mcp

/** A prompt exposed by an MCP server via `prompts/list` (#1734). */
data class McpPromptInfo(
    val name: String,
    val title: String? = null,
    val description: String? = null,
    val arguments: List<McpPromptArgument> = emptyList(),
)
