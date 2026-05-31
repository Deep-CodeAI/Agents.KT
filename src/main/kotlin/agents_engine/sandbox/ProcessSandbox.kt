package agents_engine.sandbox

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Layer 2 (#2891 / #1916), first slice (#2906): OS-level write-confinement for a
 * subprocess, via macOS **Seatbelt** (`/usr/bin/sandbox-exec`).
 *
 * [run] launches a command under a generated Seatbelt profile that denies
 * everything by default and allows file *writes* only under [writableRoot]. A
 * write to any path outside that subtree is blocked by the kernel — not just the
 * in-JVM Layer-1 gate (#2890). Reads and process exec stay allowed so the command
 * (e.g. `/bin/sh`) can load and run.
 *
 * macOS only for now; [isSupported] is false elsewhere and [run] throws there.
 * The Linux (bwrap/firejail) backend and the general `ToolPolicy` → profile
 * mapping are later slices of #2891.
 */
class ProcessSandbox(writableRoot: Path) {

    // Canonical, symlink-resolved root. REQUIRED: macOS `/tmp` -> `/private/tmp`,
    // and Seatbelt `subpath` matches the canonical path, so a logical root would
    // silently match nothing and block every write.
    private val realRoot: Path = writableRoot.toRealPath()

    fun run(command: List<String>, stdin: String? = null, timeout: Duration = 10.seconds): SandboxResult {
        check(isSupported()) {
            "ProcessSandbox requires macOS with $SANDBOX_EXEC (Linux backend is #2892). " +
                "os.name='${System.getProperty("os.name")}'"
        }
        require(command.isNotEmpty()) { "command must not be empty" }

        val argv = listOf(SANDBOX_EXEC, "-p", seatbeltProfile(realRoot)) + command
        val process = ProcessBuilder(argv).redirectErrorStream(false).start()

        // Drain stdout/stderr on daemon threads to avoid a full-pipe deadlock
        // (same pattern as StdioMcpTransport.forProcess).
        val out = StringBuilder()
        val err = StringBuilder()
        val outDrain = drain(process.inputStream, out)
        val errDrain = drain(process.errorStream, err)

        process.outputStream.use { os ->
            if (stdin != null) os.write(stdin.toByteArray(Charsets.UTF_8))
        }

        val finished = process.waitFor(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroy()
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
            outDrain.join(1_000)
            errDrain.join(1_000)
            return SandboxResult(
                exitCode = -1,
                stdout = out.toString(),
                stderr = (err.toString() + "\n[sandbox] command timed out after $timeout").trim(),
            )
        }
        outDrain.join(2_000)
        errDrain.join(2_000)
        return SandboxResult(process.exitValue(), out.toString(), err.toString())
    }

    private fun drain(stream: InputStream, sink: StringBuilder): Thread =
        Thread {
            runCatching {
                stream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                    synchronized(sink) { sink.append(line).append('\n') }
                }
            }
        }.apply { isDaemon = true; name = "ProcessSandbox-drain"; start() }

    companion object {
        private const val SANDBOX_EXEC = "/usr/bin/sandbox-exec"

        fun isSupported(): Boolean =
            System.getProperty("os.name", "").contains("Mac", ignoreCase = true) &&
                Files.isExecutable(Path.of(SANDBOX_EXEC))

        /**
         * Pure: the Seatbelt SBPL profile that denies by default and confines
         * file writes to [realRoot] (which must already be canonical). Reads and
         * process operations stay allowed so the command can load and exec.
         */
        fun seatbeltProfile(realRoot: Path): String = buildString {
            append("(version 1)")
            append("(deny default)")
            append("(allow process*)")
            append("(allow file-read*)")
            append("(allow file-write* (subpath ${sbplString(realRoot.toString())}))")
        }

        /** Quote a path as an SBPL string literal (escape backslash, then double-quote). */
        private fun sbplString(value: String): String =
            "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }
}

data class SandboxResult(val exitCode: Int, val stdout: String, val stderr: String) {
    val ok: Boolean get() = exitCode == 0
}
