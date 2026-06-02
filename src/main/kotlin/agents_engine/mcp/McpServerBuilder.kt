package agents_engine.mcp

import java.io.File

/**
 * Per-server builder for the agent-level `mcp { server(name) { } }` DSL ([AgentMcpDsl]). Declares
 * exactly one transport (HTTP `url`, stdio `command`, or `host`+`port`) and builds the connected
 * [McpClient]; validation failures fail the agent build fast.
 */
class McpServerBuilder internal constructor(private val name: String) {
    var url: String? = null
    var command: List<String>? = null
    var host: String? = null
    var port: Int? = null
    var auth: McpAuth = McpAuth.None
    var env: Map<String, String> = emptyMap()
    var workingDir: File? = null
    var stderrSink: (String) -> Unit = {}

    internal fun build(): McpClient {
        val isHttp = url != null
        val isStdio = command != null
        val tcpHost = host
        val tcpPort = port
        val isTcp = tcpHost != null || tcpPort != null

        val transports = listOf(isHttp, isStdio, isTcp).count { it }
        require(transports == 1) {
            "MCP server \"$name\" must declare exactly one transport (url=, command=, or host+port=). Got $transports."
        }
        if (isTcp) {
            require(tcpHost != null && tcpPort != null) {
                "MCP server \"$name\": TCP transport requires both host and port."
            }
        }
        if (auth !is McpAuth.None) {
            require(isHttp) {
                "MCP server \"$name\": auth is only supported on HTTP transport (url=). Connection identity is the auth on stdio/TCP."
            }
        }

        return when {
            isHttp -> McpClient.connect(url!!, auth)
            isStdio -> McpClient.connectStdio(command!!, env, workingDir, stderrSink)
            isTcp -> McpClient.connectTcp(tcpHost!!, tcpPort!!)
            else -> error("unreachable; validated above")
        }
    }
}
