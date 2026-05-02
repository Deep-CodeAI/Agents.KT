package agents_engine.mcp

import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/**
 * Stdio transport. Two creation paths:
 *
 * - [forStreams] — wraps a generic input/output stream pair. Used by tests
 *   (in-process pipes) and by anyone wiring a custom IPC channel.
 * - [forProcess] — spawns a child process and reads from its stdout / writes
 *   to its stdin. Stderr is streamed line-by-line to [stderrSink] (default: drop).
 */
internal class StdioMcpTransport private constructor(
    input: InputStream,
    output: OutputStream,
    private val onClose: () -> Unit,
) : LineDelimitedMcpTransport(input, output) {

    override fun close() {
        super.close()
        runCatching { onClose() }
    }

    companion object {
        fun forStreams(input: InputStream, output: OutputStream, onClose: () -> Unit = {}): StdioMcpTransport =
            StdioMcpTransport(input, output, onClose)

        fun forProcess(
            command: List<String>,
            env: Map<String, String> = emptyMap(),
            workingDir: java.io.File? = null,
            stderrSink: (String) -> Unit = {},
        ): StdioMcpTransport {
            val process = ProcessBuilder(command).apply {
                environment().putAll(env)
                if (workingDir != null) directory(workingDir)
                redirectErrorStream(false)
            }.start()

            // Drain stderr on a daemon thread so the child doesn't block on a full buffer.
            val stderrThread = Thread({
                process.errorStream.bufferedReader().use { r ->
                    while (true) stderrSink(r.readLine() ?: return@use)
                }
            }, "MCP-stdio-stderr-${process.pid()}").apply { isDaemon = true; start() }

            return StdioMcpTransport(
                input = process.inputStream,
                output = process.outputStream,
                onClose = {
                    process.destroy()
                    if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
                    runCatching { stderrThread.join(500) }
                },
            )
        }
    }
}
