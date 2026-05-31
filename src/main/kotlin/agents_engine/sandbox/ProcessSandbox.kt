package agents_engine.sandbox

import agents_engine.core.ToolNetworkPolicy
import agents_engine.core.ToolPolicy
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Layer 2 (#2891 / #1916): OS-level isolation for a subprocess, via macOS
 * **Seatbelt** (`/usr/bin/sandbox-exec`).
 *
 * [run] launches a command under a generated Seatbelt profile that denies
 * everything by default and allows file **writes** only under the configured
 * roots (#2906 single-root PoC; #2909 multi-root from a tool's [ToolPolicy]).
 * A write to any path outside those roots is blocked by the **kernel** — not just
 * the in-JVM Layer-1 gate (#2890). Reads and process exec stay allowed so the
 * command (e.g. `/bin/sh`) can load and run; network stays blocked unless opened.
 *
 * macOS only for now ([isSupported] is false elsewhere and [run] throws). The
 * Linux (bwrap/firejail) backend is #2892; network hostname filtering is the
 * proxy (#2893); read-confinement and the `process { }` DSL remain in #2891.
 */
class ProcessSandbox private constructor(
    private val realRoots: List<Path>,
    private val allowNetwork: Boolean,
) {

    /**
     * Confine writes to a single folder (#2906). The folder is canonicalized
     * (macOS `/tmp` -> `/private/tmp`; Seatbelt matches the canonical path).
     */
    constructor(writableRoot: Path) : this(listOf(canonicalPath(writableRoot)), allowNetwork = false)

    fun run(command: List<String>, stdin: String? = null, timeout: Duration = 10.seconds): SandboxResult {
        check(isSupported()) {
            "ProcessSandbox requires macOS with $SANDBOX_EXEC (Linux backend is #2892). " +
                "os.name='${System.getProperty("os.name")}'"
        }
        require(command.isNotEmpty()) { "command must not be empty" }

        val argv = listOf(SANDBOX_EXEC, "-p", seatbeltProfile(realRoots, allowNetwork)) + command
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

        /** Confine writes to the given roots (canonicalized). */
        fun forWritableRoots(roots: List<Path>, allowNetwork: Boolean = false): ProcessSandbox =
            ProcessSandbox(roots.map(::canonicalPath), allowNetwork)

        /**
         * Build a sandbox from a tool's declared [ToolPolicy]: write roots are the
         * directory prefixes of `filesystem.write` globs; network is opened only for
         * `network = AllowAll`. (`Hosts` filtering needs the proxy #2893, so it stays
         * blocked here; reads stay broad — read-confinement is a later slice.)
         */
        fun forPolicy(policy: ToolPolicy): ProcessSandbox {
            val roots = policy.filesystem.write.globs
                .map { canonicalPath(Path.of(globToWriteRoot(it))) }
            return ProcessSandbox(roots, allowNetwork = policy.network == ToolNetworkPolicy.AllowAll)
        }

        /**
         * Pure: the Seatbelt SBPL profile. Denies by default; allows reads + process
         * exec (so the command can load); allows file writes under each (canonical)
         * root; opens network only when [allowNetwork].
         */
        fun seatbeltProfile(writeRoots: List<Path>, allowNetwork: Boolean = false): String = buildString {
            append("(version 1)")
            append("(deny default)")
            append("(allow process*)")
            append("(allow file-read*)")
            writeRoots.forEach { append("(allow file-write* (subpath ${sbplString(it.toString())}))") }
            if (allowNetwork) append("(allow network*)")
        }

        /** Single-root convenience (#2906); delegates to the list form. */
        fun seatbeltProfile(realRoot: Path): String = seatbeltProfile(listOf(realRoot))

        // The literal directory prefix of a write glob — the deepest path that
        // contains no wildcard. Examples (slash-star avoided here because Kotlin
        // block comments nest):
        //   "/uploads/[**]"  -> "/uploads"
        //   "/a/b/[*].txt"   -> "/a/b"
        //   "/a/foo[*]bar"   -> "/a"   (mid-segment wildcard -> its parent dir)
        //   no wildcard      -> the path itself
        fun globToWriteRoot(glob: String): String {
            val wildcards = charArrayOf('*', '?', '[', '{')
            val idx = glob.indexOfFirst { it in wildcards }
            if (idx < 0) return glob.trimEnd('/').ifEmpty { "/" }
            val prefix = glob.substring(0, idx)
            val cut = if (prefix.endsWith('/')) prefix else prefix.substringBeforeLast('/', "/")
            return cut.trimEnd('/').ifEmpty { "/" }
        }

        /** Quote a path as an SBPL string literal (escape backslash, then double-quote). */
        private fun sbplString(value: String): String =
            "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }
}

/**
 * Resolve symlinks on the deepest existing ancestor and re-append the
 * not-yet-existing tail, so a not-yet-created leaf under `/tmp` still gets the
 * `/tmp` -> `/private/tmp` canonicalization that Seatbelt's `subpath` requires.
 */
private fun canonicalPath(p: Path): Path {
    var existing: Path? = p
    val tail = ArrayDeque<Path>()
    while (existing != null && !Files.exists(existing)) {
        existing.fileName?.let { tail.addFirst(it) }
        existing = existing.parent
    }
    if (existing == null) return p.toAbsolutePath().normalize()
    var real = existing.toRealPath()
    for (segment in tail) real = real.resolve(segment)
    return real.normalize()
}

data class SandboxResult(val exitCode: Int, val stdout: String, val stderr: String) {
    val ok: Boolean get() = exitCode == 0
}
