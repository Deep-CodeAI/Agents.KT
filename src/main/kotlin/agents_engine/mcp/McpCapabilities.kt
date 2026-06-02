package agents_engine.mcp

/**
 * The capability matrix an MCP server reports at handshake — what it says it can do (#1734).
 * Component capabilities live in sibling files: [McpToolsCapability], [McpResourcesCapability],
 * [McpPromptsCapability].
 */
data class McpCapabilities(
    /** Tool listing + invocation. Null when the server doesn't expose tools at all. */
    val tools: McpToolsCapability? = null,
    /** Resource listing + reading. */
    val resources: McpResourcesCapability? = null,
    /** Prompt listing + retrieval. */
    val prompts: McpPromptsCapability? = null,
    /** Server can emit `logging/message` notifications. */
    val logging: Boolean = false,
    /** Server supports `completion/complete` for argument completion. */
    val completions: Boolean = false,
    /** Catch-all for capabilities not yet standardized in the MCP spec. */
    val experimental: Map<String, Any?> = emptyMap(),
)
