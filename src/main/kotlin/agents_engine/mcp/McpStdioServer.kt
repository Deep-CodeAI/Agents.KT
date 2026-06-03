package agents_engine.mcp

import agents_engine.core.Agent
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * `agents_engine/mcp/McpStdioServer.kt` — exposes an [Agent]'s MCP
 * tools/prompts/resources over line-delimited stdio. Reads one JSON-RPC
 * envelope per stdin line and writes response envelopes only to stdout,
 * preserving stdout as protocol traffic. Notifications produce no
 * response. Drives the shared [McpDispatcher] directly (#2795) so HTTP and
 * stdio stay in behavioral lockstep, with no HTTP server in the stdio path.
 */
class McpStdioServer private constructor(
    private val dispatcher: McpDispatcher,
) {

    fun serve(
        stdin: InputStream = System.`in`,
        stdout: OutputStream = System.out,
    ) {
        val reader = stdin.bufferedReader(Charsets.UTF_8)
        val writer = stdout.bufferedWriter(Charsets.UTF_8)
        try {
            while (true) {
                val line = reader.readLine() ?: return
                if (line.isBlank()) continue
                val response = dispatcher.dispatchEnvelope(line)
                if (response != null) {
                    writer.write(response)
                    writer.newLine()
                    writer.flush()
                }
            }
        } catch (e: IOException) {
            System.err.println("MCP stdio server stopped: ${e.message ?: e.toString()}")
        }
    }

    companion object {
        fun from(agent: Agent<*, *>, block: McpExposeBuilder.() -> Unit): McpStdioServer =
            McpStdioServer(McpDispatcher.from(agent, block))
    }
}
