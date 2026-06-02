package agents_engine.mcp

/** The outcome of an [McpServerAuth] check: allow a principal, or reject with an HTTP status. */
sealed interface McpAuthDecision {
    data class Allow(val principal: ClientPrincipal) : McpAuthDecision
    data class Reject(val statusCode: Int, val message: String) : McpAuthDecision
}
