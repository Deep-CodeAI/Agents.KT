package agents_engine.mcp

/** Authenticated caller identity for inbound MCP server requests. */
data class ClientPrincipal(
    val id: String,
    val attributes: Map<String, String> = emptyMap(),
) {
    companion object {
        val TrustedLocal: ClientPrincipal = ClientPrincipal(
            id = "trusted-local",
            attributes = mapOf("transport" to "local"),
        )
    }
}
