package agents_engine.mcp

/**
 * Small exception hierarchy for the MCP layer (#2796). Backed by `IllegalStateException` so existing
 * `catch (e: IllegalStateException)` call sites keep working, while the type-discriminated subclasses
 * let callers `catch` the failure category (transport vs protocol vs tool) instead of grepping
 * messages.
 */
internal sealed class McpException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause) {
    /** Transport layer failure (network, malformed HTTP, stdio EOF). */
    class Transport(message: String, cause: Throwable? = null) : McpException(message, cause)

    /** Protocol-level failure (server returned a JSON-RPC `error`, malformed envelope, missing fields). */
    class Protocol(message: String, val code: Int? = null, cause: Throwable? = null) : McpException(message, cause)

    /** Tool-level failure (tool ran but returned isError or threw). */
    class ToolFailure(message: String, cause: Throwable? = null) : McpException(message, cause)
}
