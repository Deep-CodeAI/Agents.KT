package agents_engine.mcp

/**
 * A server-side prompt registration (#1796). Mirrors the MCP wire shape for prompts: a name,
 * description, argument spec, and a render closure that turns the call-time args map into the
 * prompt text.
 */
internal data class RegisteredPrompt(
    val name: String,
    val description: String,
    val arguments: List<McpPromptArgument>,
    val render: (Map<String, Any?>) -> String,
)
