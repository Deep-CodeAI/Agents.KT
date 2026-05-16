package agents_engine.mcp

import agents_engine.generation.LenientJsonParser
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter

/**
 * `agents_engine/mcp/LineDelimitedMcpTransport.kt` — abstract base for
 * the stdio + TCP transports. JSON-RPC envelopes are one line each,
 * `\n`-terminated. Notifications from the server (no `id` field) are
 * dropped silently. Single-flight: callers must serialize their
 * `rpc()` calls (the `McpClient` is single-threaded, so the contract
 * matches). See
 * `src/main/resources/internals-agent/mcp/LineDelimitedMcpTransport.md`
 * (#1837 / #1878).
 */

/**
 * Shared logic for line-delimited JSON-RPC transports (stdio, TCP).
 *
 * Each request envelope is written as a single line (terminated by `\n`).
 * Each response envelope is read as a single line. Notifications coming
 * from the server (no `id` field) are dropped silently.
 *
 * Single-flight: callers must serialize their `rpc()` calls. `McpClient`
 * is single-threaded so this matches.
 */
internal abstract class LineDelimitedMcpTransport(input: InputStream, output: OutputStream) : McpTransport {

    private val writer: BufferedWriter = BufferedWriter(OutputStreamWriter(output, Charsets.UTF_8))
    private val reader: BufferedReader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))

    final override fun rpc(envelope: String): String {
        val expectedId = extractId(envelope)
        writeLine(envelope)
        return readResponseFor(expectedId)
    }

    final override fun notify(envelope: String) { writeLine(envelope) }

    override fun close() {
        runCatching { writer.flush() }
        runCatching { writer.close() }
        runCatching { reader.close() }
    }

    private fun writeLine(envelope: String) {
        writer.write(envelope)
        writer.write("\n")
        writer.flush()
    }

    private fun readResponseFor(expectedId: Any?): String {
        while (true) {
            val line = reader.readLine()
                ?: error("MCP transport closed before response (expected id=$expectedId)")
            if (line.isBlank()) continue
            val parsed = LenientJsonParser.parse(line) as? Map<*, *>
                ?: continue
            if ("id" !in parsed) continue  // notification — drop
            if (idsEqual(parsed["id"], expectedId)) return line
            // Mismatched response id — protocol violation in single-flight; ignore and keep reading.
        }
    }

    private fun extractId(envelope: String): Any? {
        val parsed = LenientJsonParser.parse(envelope) as? Map<*, *> ?: return null
        return parsed["id"]
    }

    private fun idsEqual(a: Any?, b: Any?): Boolean {
        if (a == null || b == null) return a == b
        // JSON-RPC ids may serialize as Number or String; normalize to string for comparison.
        return a.toString() == b.toString()
    }
}
