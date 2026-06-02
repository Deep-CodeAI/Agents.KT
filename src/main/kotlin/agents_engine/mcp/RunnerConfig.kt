package agents_engine.mcp

/** Parsed CLI configuration for [McpRunner] — transport choice, exposed skill names, and flags. */
internal data class RunnerConfig(
    val port: Int,
    val exposeNames: List<String>,
    val helpRequested: Boolean,
    val versionRequested: Boolean,
    val stdioRequested: Boolean,
    val errors: List<String>,
    val onStarted: (McpServer) -> Unit,
    val onStdioStarted: (McpStdioServer) -> Unit,
)
