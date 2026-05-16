package agents_engine.runtime.internals

import agents_engine.mcp.McpServer

/**
 * #1837 — runner for the InternalsAgent MCP server. Builds the agent,
 * exposes every registered skill as an MCP tool, and runs until killed.
 *
 * Default port is 8765 (chosen to be memorable and unlikely to collide).
 * Override via the first CLI arg.
 *
 * IDE integration: add to your Claude Desktop config under `mcpServers`:
 * ```json
 * {
 *   "mcpServers": {
 *     "agents-kt-internals": {
 *       "url": "http://localhost:8765/mcp"
 *     }
 *   }
 * }
 * ```
 *
 * Cursor: the equivalent in its `~/.cursor/mcp.json` file.
 *
 * After connecting, the IDE LLM can call any registered skill as a tool —
 * each returns the curated KDoc adjunct for the corresponding source file.
 */
fun main(args: Array<String>) {
    val port = args.firstOrNull()?.toIntOrNull() ?: DEFAULT_PORT
    val agent = buildInternalsAgent()
    val server = McpServer.from(agent) {
        this.port = port
        agent.skills.keys.forEach { skillName -> expose(skillName) }
    }.start()
    println("─".repeat(60))
    println("agents-kt InternalsAgent MCP server")
    println("URL: ${server.url}")
    println("Skills exposed (${agent.skills.size}): ${agent.skills.keys.joinToString(", ")}")
    println("─".repeat(60))
    println("Add this URL to your IDE's MCP config to query Agents.KT internals.")
    println("Press Ctrl+C to stop.")
    Thread.currentThread().join()
}

private const val DEFAULT_PORT: Int = 8765
