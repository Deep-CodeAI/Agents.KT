package agents_engine.mcp

/**
 * A server-side resource registration (#1810). Mirrors the MCP wire shape for resources: URI (the
 * addressable handle), display name, optional description and MIME type, and a `read` closure
 * invoked on `resources/read` to produce the resource's text content.
 */
internal data class RegisteredResource(
    val uri: String,
    val name: String,
    val description: String?,
    val mimeType: String?,
    val read: () -> String,
)
