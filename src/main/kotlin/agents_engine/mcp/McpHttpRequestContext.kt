package agents_engine.mcp

/** Minimal HTTP request view exposed to [McpServerAuth] implementations. */
data class McpHttpRequestContext(
    val headers: Map<String, List<String>>,
    val remoteAddress: String?,
) {
    fun firstHeader(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }
            ?.value
            ?.firstOrNull()
}
