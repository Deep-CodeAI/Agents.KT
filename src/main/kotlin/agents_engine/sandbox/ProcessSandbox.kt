package agents_engine.sandbox

import agents_engine.core.ToolNetworkPolicy
import agents_engine.core.ToolPolicy
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Layer 2 (#2891 / #1916): OS-level isolation for a subprocess. Two backends,
 * picked by OS at [run] time: macOS **Seatbelt** (`/usr/bin/sandbox-exec`, #2906)
 * and Linux **bubblewrap** (`bwrap`, #2892).
 *
 * [run] launches the command confined so that file **writes** are allowed only
 * under the configured roots (#2909 derives them from a tool's [ToolPolicy]); a
 * write anywhere else is blocked by the **kernel**, not just the in-JVM Layer-1
 * gate (#2890). Reads + process exec stay allowed so the command (e.g. `/bin/sh`)
 * can load; network is blocked unless opened.
 *
 * [isSupported] is true on macOS-with-sandbox-exec or Linux-with-bwrap; [run]
 * throws elsewhere. The firejail fallback, network hostname filtering (#2893),
 * read-confinement, and the `process { }` DSL remain follow-ups.
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
        require(command.isNotEmpty()) { "command must not be empty" }

        // Dispatch by OS: Seatbelt on macOS, bwrap on Linux. Both confine the
        // subprocess to the same write roots; only the wrapping differs.
        val sandboxExec = macSandboxExec()
        val bwrap = linuxBwrap()
        val argv = when {
            sandboxExec != null ->
                listOf(sandboxExec.toString(), "-p", seatbeltProfile(realRoots, allowNetwork)) + command
            bwrap != null ->
                listOf(bwrap.toString()) + bwrapArgs(realRoots, allowNetwork) + command
            else -> error(
                "ProcessSandbox needs macOS+sandbox-exec or Linux+bwrap (Wasm/Docker = #2894/#2895). " +
                    "os.name='${System.getProperty("os.name")}'",
            )
        }
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
            if (!process.waitFor(GRACEFUL_DESTROY_WAIT_SEC, TimeUnit.SECONDS)) process.destroyForcibly()
            outDrain.join(DRAIN_JOIN_AFTER_KILL_MS)
            errDrain.join(DRAIN_JOIN_AFTER_KILL_MS)
            return SandboxResult(
                exitCode = -1,
                stdout = out.toString(),
                stderr = (err.toString() + "\n[sandbox] command timed out after $timeout").trim(),
            )
        }
        outDrain.join(DRAIN_JOIN_TIMEOUT_MS)
        errDrain.join(DRAIN_JOIN_TIMEOUT_MS)
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
        private const val BWRAP = "bwrap"

        // Grace period for the process to exit after destroy() before destroyForcibly().
        private const val GRACEFUL_DESTROY_WAIT_SEC = 2L
        // How long to wait for the stdout/stderr drain threads to finish.
        private const val DRAIN_JOIN_TIMEOUT_MS = 2_000L
        private const val DRAIN_JOIN_AFTER_KILL_MS = 1_000L

        private fun osName() = System.getProperty("os.name", "")

        /** macOS `sandbox-exec` when this is a Mac and the binary is present; else null. */
        private fun macSandboxExec(): Path? =
            Path.of(SANDBOX_EXEC).takeIf { osName().contains("Mac", ignoreCase = true) && Files.isExecutable(it) }

        /** Linux `bwrap` (bubblewrap) resolved from PATH when this is Linux; else null. */
        private fun linuxBwrap(): Path? =
            if (!osName().contains("Linux", ignoreCase = true)) {
                null
            } else {
                (System.getenv("PATH") ?: "").split(File.pathSeparatorChar)
                    .firstNotNullOfOrNull { dir -> Path.of(dir, BWRAP).takeIf { Files.isExecutable(it) } }
            }

        /** True when an OS sandbox backend is available (macOS Seatbelt or Linux bwrap). */
        fun isSupported(): Boolean = macSandboxExec() != null || linuxBwrap() != null

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

        /**
         * Pure: Linux bubblewrap args (#2892). Bind the whole filesystem
         * **read-only**, give the command a fresh `/proc` + `/dev`, re-bind each
         * (canonical) write root **read-write**, and — unless [allowNetwork] —
         * unshare the network namespace. A write outside a root hits the read-only
         * mount ("Read-only file system"); reads + exec stay allowed.
         */
        fun bwrapArgs(writeRoots: List<Path>, allowNetwork: Boolean = false): List<String> = buildList {
            add("--ro-bind"); add("/"); add("/")
            add("--proc"); add("/proc")
            add("--dev"); add("/dev")
            writeRoots.forEach { add("--bind"); add(it.toString()); add(it.toString()) }
            if (!allowNetwork) add("--unshare-net")
        }

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
