package agents_engine.runtime.internals

import agents_engine.mcp.McpServer
import agents_engine.mcp.McpStdioServer
import java.io.InputStream
import java.io.OutputStream
import java.io.PrintStream

/**
 * #1837 — runner for the InternalsAgent MCP server. Builds the agent,
 * exposes every registered skill as an MCP tool, and runs until killed
 * in HTTP mode or stdin closes in `--stdio` mode.
 *
 * Default port is 8765 (chosen to be memorable and unlikely to collide).
 * Override via the first CLI arg. Pass `--stdio` for MCP stdio.
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
    runInternalsAgent(args)
}

internal fun runInternalsAgent(
    args: Array<String>,
    stdin: InputStream = System.`in`,
    stdout: OutputStream = System.out,
): Int {
    val agent = buildInternalsAgent()
    if ("--stdio" in args) {
        McpStdioServer.from(agent) {
            agent.skills.keys.forEach { skillName -> expose(skillName) }
        }.serve(stdin, stdout)
        return 0
    }

    val port = args.firstOrNull()?.toIntOrNull() ?: DEFAULT_PORT
    val server = McpServer.from(agent) {
        this.port = port
        agent.skills.keys.forEach { skillName -> expose(skillName) }
    }.start()
    val out = PrintStream(stdout, true)
    out.println("─".repeat(60))
    out.println("agents-kt InternalsAgent MCP server")
    out.println("URL: ${server.url}")
    out.println("Skills exposed (${agent.skills.size}): ${agent.skills.keys.joinToString(", ")}")
    out.println("─".repeat(60))
    out.println("Add this URL to your IDE's MCP config to query Agents.KT internals.")
    out.println("Press Ctrl+C to stop.")
    Thread.currentThread().join()
    return 0
}

private const val DEFAULT_PORT: Int = 8765
