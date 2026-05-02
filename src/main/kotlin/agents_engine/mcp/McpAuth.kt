package agents_engine.mcp

/**
 * Auth scheme for an MCP transport. Today only HTTP cares — stdio and TCP
 * derive auth from connection identity. Sealed so future variants
 * (`OAuth`, `ApiKeyHeader`, etc.) can plug in without overload sprawl.
 */
sealed interface McpAuth {
    /** No credentials sent. Default. */
    object None : McpAuth

    /** Sends `Authorization: Bearer <token>` on every request. */
    data class Bearer(val token: String) : McpAuth
}
