package agents_engine.mcp

/** An RFC 6570 resource template exposed via `resources/templates/list` (#1734). */
data class McpResourceTemplateInfo(
    /** RFC 6570 URI template (e.g., `file:///{path}`). */
    val uriTemplate: String,
    val name: String,
    val title: String? = null,
    val description: String? = null,
    val mimeType: String? = null,
)
