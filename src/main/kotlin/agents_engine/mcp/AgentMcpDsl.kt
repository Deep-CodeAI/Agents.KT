package agents_engine.mcp

import agents_engine.core.Agent
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
