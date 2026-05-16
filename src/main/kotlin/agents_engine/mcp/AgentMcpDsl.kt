package agents_engine.mcp

import agents_engine.core.Agent
import java.io.File
import java.util.WeakHashMap

/**
 * `agents_engine/mcp/AgentMcpDsl.kt` — the agent-level `mcp { server(...) }`
 * DSL for declarative MCP server registration. Each `server` connects at
 * agent-construction time and registers its tools into `agent.toolMap`
 * prefixed by server name. v0.5.0 added `toolSkills()` / `promptSkills()` /
 * `resourceSkills()` shortcuts that expose every MCP capability shape as
 * `Skill<Map<String, Any?>, String>`. Connection failures fail the build
 * (fail-fast). Clients are retained on the agent via [mcpClients] for
 * lifecycle. See `src/main/resources/internals-agent/mcp/AgentMcpDsl.md`
 * (#1837 / #1876).
 */

/**
 * Agent-level DSL for declarative MCP server registration.
 *
 * ```kotlin
 * agent<X, Y>("coder") {
 *     mcp {
 *         server("github") {
 *             url = "https://api.github.com/mcp"
 *             auth = McpAuth.Bearer("ghp_xxx")
 *         }
 *         server("filesystem") {
 *             command = listOf("npx", "@modelcontextprotocol/server-filesystem", "/src")
 *         }
 *         server("internal") { host = "mcp.internal"; port = 9000 }
 *     }
 *     // tools registered as github.create_pr, filesystem.read_file, internal.foo
 * }
 * ```
 *
 * Each `server(name) { }` connects at agent-construction time and registers its
 * tools into the agent's `toolMap` with prefix = server name. Connection failures
 * fail the agent build (fail fast). Connected clients are retained on the agent
 * and accessible via [mcpClients] for lifecycle control (e.g., `close()` in tests).
 */
fun <IN, OUT> Agent<IN, OUT>.mcp(block: McpServersBuilder.() -> Unit) {
    val builder = McpServersBuilder().apply(block)
    val connected = builder.connectAll()  // throws on validation/connection failure → agent build fails
    synchronized(attachedClients) {
        attachedClients.getOrPut(this) { mutableListOf() }.addAll(connected.map { it.second })
    }
    connected.forEach { (prefix, client) ->
        client.toolDefs(prefix = prefix).forEach { td -> registerTool(td) }
    }
}

/** MCP clients connected via the agent's `mcp { }` block. Empty if none configured. */
val <IN, OUT> Agent<IN, OUT>.mcpClients: List<McpClient>
    get() = synchronized(attachedClients) { attachedClients[this]?.toList() ?: emptyList() }

private val attachedClients = WeakHashMap<Agent<*, *>, MutableList<McpClient>>()

class McpServersBuilder internal constructor() {
    private val configs = linkedMapOf<String, McpServerBuilder>()

    fun server(name: String, block: McpServerBuilder.() -> Unit) {
        require(name !in configs) { "Duplicate MCP server name: \"$name\"" }
        configs[name] = McpServerBuilder(name).apply(block)
    }

    internal fun connectAll(): List<Pair<String, McpClient>> =
        configs.map { (name, b) -> name to b.build() }
}

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
