package agents_engine.mcp

/**
 * MCP server that exposes a single stateful `fib_next` tool, hosted on [MockMcpServer].
 *
 * Each call advances the server's internal Fibonacci state and returns the next number
 * as a text content block. State is per-server-instance.
 */
object FibonacciMcpServer {
    fun start(): MockMcpServer = MockMcpServer.start { installFibonacciTool() }
}

/**
 * Installs the `fib_next` tool into any [MockMcpServerBuilder]. Lets the same tool
 * logic be hosted over HTTP, TCP, or stdio without duplication.
 */
fun MockMcpServerBuilder.installFibonacciTool() {
    var prev = 0L
    var curr = 0L  // 0 means "no number yet"

    tool("fib_next") {
        description = "Returns the next Fibonacci number. Server maintains state across calls. No arguments."
        inputSchema = """{"type":"object","properties":{},"additionalProperties":false}"""
        respond { _ ->
            val next: Long = when {
                curr == 0L -> { prev = 0L; curr = 1L; 1L }
                else -> {
                    val n = prev + curr
                    prev = curr
                    curr = n
                    n
                }
            }
            listOf(textBlock(next.toString()))
        }
    }
}
