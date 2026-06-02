package agents_engine.mcp

/** DSL builder for [McpRunner] — port/stdio transport, exposed skill names, and test start hooks. */
class McpRunnerBuilder internal constructor() {
    var port: Int = 0
    var stdio: Boolean = false
    internal val blockExposes = mutableListOf<String>()
    internal var onStartedHandler: (McpServer) -> Unit = {}
    internal var onStdioStartedHandler: (McpStdioServer) -> Unit = {}

    fun expose(vararg names: String) { blockExposes.addAll(names) }

    /** Test hook: invoked after the server starts, with the running [McpServer]. */
    var onStarted: (McpServer) -> Unit
        get() = onStartedHandler
        set(value) { onStartedHandler = value }

    /** Test hook: invoked before stdio serving begins, with the [McpStdioServer]. */
    var onStdioStarted: (McpStdioServer) -> Unit
        get() = onStdioStartedHandler
        set(value) { onStdioStartedHandler = value }
}
