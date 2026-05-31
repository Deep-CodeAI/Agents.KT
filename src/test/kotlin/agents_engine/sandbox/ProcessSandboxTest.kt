package agents_engine.sandbox

import agents_engine.core.ToolRisk
import agents_engine.core.toolPolicy
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #2906 (first slice of Layer 2 / #2891) — pure tests for the Seatbelt profile.
 * These run on every platform (no subprocess), so the profile contract is pinned
 * even on Linux CI where the OS-gated integration tests below are skipped.
 */
class SeatbeltProfileTest {

    @Test fun `profile denies by default and confines writes to the given root`() {
        val profile = ProcessSandbox.seatbeltProfile(Path.of("/private/tmp/sbx-root"))
        assertTrue(profile.startsWith("(version 1)"), "profile: $profile")
        assertTrue("(deny default)" in profile, "must deny by default: $profile")
        assertTrue("file-read*" in profile, "reads allowed (needed to exec): $profile")
        assertTrue("file-write*" in profile, "writes gated: $profile")
        assertTrue("(subpath \"/private/tmp/sbx-root\")" in profile, "write confined to the quoted root: $profile")
    }

    @Test fun `profile quotes a root containing a space`() {
        val profile = ProcessSandbox.seatbeltProfile(Path.of("/private/tmp/a b"))
        assertTrue("(subpath \"/private/tmp/a b\")" in profile, "spaced path stays a single quoted literal: $profile")
    }

    @Test fun `globToWriteRoot strips the wildcard tail back to the containing directory`() {
        assertEquals("/uploads", ProcessSandbox.globToWriteRoot("/uploads/**"))
        assertEquals("/a/b", ProcessSandbox.globToWriteRoot("/a/b/*.txt"))
        assertEquals("/a", ProcessSandbox.globToWriteRoot("/a/foo*bar")) // mid-segment wildcard -> parent dir
        assertEquals("/exact/file.txt", ProcessSandbox.globToWriteRoot("/exact/file.txt")) // no wildcard
        assertEquals("/data", ProcessSandbox.globToWriteRoot("/data/{x,y}/**"))
    }

    @Test fun `profile emits one write rule per root and toggles network`() {
        val p = ProcessSandbox.seatbeltProfile(
            listOf(Path.of("/private/tmp/a"), Path.of("/private/tmp/b")),
            allowNetwork = true,
        )
        assertTrue("(subpath \"/private/tmp/a\")" in p, p)
        assertTrue("(subpath \"/private/tmp/b\")" in p, p)
        assertTrue("(allow network*)" in p, "allowNetwork must open network: $p")

        val noNet = ProcessSandbox.seatbeltProfile(listOf(Path.of("/private/tmp/a")))
        assertFalse("network" in noNet, "deny-default leaves network blocked unless opened: $noNet")
    }

    @Test fun `empty write roots produce a deny-all-writes profile`() {
        val p = ProcessSandbox.seatbeltProfile(emptyList())
        assertTrue("(deny default)" in p)
        assertFalse("file-write*" in p, "no declared write root -> no write allowance: $p")
    }

    @Test fun `single-root profile delegates to the list form`() {
        assertEquals(
            ProcessSandbox.seatbeltProfile(listOf(Path.of("/private/tmp/x"))),
            ProcessSandbox.seatbeltProfile(Path.of("/private/tmp/x")),
        )
    }
}

/**
 * OS-gated integration: actually spawns `sandbox-exec` and asserts kernel-level
 * write confinement. Tagged `mac_os_only` so CI can filter (e.g. `--exclude-tag
 * mac_os_only` on Linux runners); `@EnabledOnOs(OS.MAC)` also auto-skips elsewhere.
 */
@EnabledOnOs(OS.MAC)
@Tag("mac_os_only")
@OptIn(ExperimentalPathApi::class)
class ProcessSandboxMacTest {

    private fun shWrite(text: String, target: Path): List<String> =
        listOf("/bin/sh", "-c", "printf '%s' \"\$1\" > \"\$2\"", "sh", text, target.toString())

    @Test fun `isSupported is true on macOS`() {
        assertTrue(ProcessSandbox.isSupported(), "macOS with /usr/bin/sandbox-exec")
    }

    @Test fun `write inside the sandboxed folder succeeds`() {
        val root = createTempDirectory("sbx-in").toRealPath()
        try {
            val target = root.resolve("note.txt")
            val res = ProcessSandbox(root).run(shWrite("hello sandbox", target))
            assertTrue(res.ok, "expected success, got exit=${res.exitCode} stderr=${res.stderr}")
            assertTrue(target.exists(), "file should exist inside the folder")
            assertEquals("hello sandbox", target.readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun `write outside the sandboxed folder is blocked by the kernel`() {
        val root = createTempDirectory("sbx-root").toRealPath()
        val outside = createTempDirectory("sbx-outside").toRealPath()
        try {
            val target = outside.resolve("escape.txt")
            val res = ProcessSandbox(root).run(shWrite("should not land", target))
            assertFalse(res.ok, "out-of-folder write must fail; exit=${res.exitCode}")
            assertFalse(target.exists(), "no file may be created outside the sandboxed folder")
            assertTrue(
                "permitted" in res.stderr.lowercase() || "denied" in res.stderr.lowercase(),
                "stderr should explain the denial: ${res.stderr}",
            )
        } finally {
            root.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test fun `echoToFile tool writes inside the folder and is blocked outside`() {
        val root = createTempDirectory("sbx-tool").toRealPath()
        val outside = createTempDirectory("sbx-tool-out").toRealPath()
        try {
            val tool = sandboxedEchoToFileTool(root)

            val inTarget = root.resolve("ok.txt")
            val okResult = tool.executor(mapOf("path" to inTarget.toString(), "text" to "in-policy"))
            assertEquals("ok", okResult)
            assertTrue(inTarget.exists() && inTarget.readText() == "in-policy")

            val outTarget = outside.resolve("blocked.txt")
            val denyResult = tool.executor(mapOf("path" to outTarget.toString(), "text" to "nope")) as String
            assertTrue(denyResult.startsWith("ERROR"), "out-of-folder write should report an error: $denyResult")
            assertFalse(outTarget.exists(), "no file may be created outside the sandboxed folder")
        } finally {
            root.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test fun `forWritableRoots confines writes to any of the given roots`() {
        val a = createTempDirectory("sbx-a").toRealPath()
        val b = createTempDirectory("sbx-b").toRealPath()
        val c = createTempDirectory("sbx-c").toRealPath()
        try {
            val sandbox = ProcessSandbox.forWritableRoots(listOf(a, b))
            assertTrue(sandbox.run(shWrite("x", a.resolve("f.txt"))).ok, "write to root A allowed")
            assertTrue(sandbox.run(shWrite("y", b.resolve("f.txt"))).ok, "write to root B allowed")
            val denied = sandbox.run(shWrite("z", c.resolve("f.txt")))
            assertFalse(denied.ok, "write to un-declared root C blocked")
            assertFalse(c.resolve("f.txt").exists())
        } finally {
            a.deleteRecursively(); b.deleteRecursively(); c.deleteRecursively()
        }
    }

    @Test fun `forPolicy derives the write roots from declared globs`() {
        val a = createTempDirectory("sbx-pa").toRealPath()
        val b = createTempDirectory("sbx-pb").toRealPath()
        val outside = createTempDirectory("sbx-pout").toRealPath()
        try {
            val policy = toolPolicy { filesystem { write("$a/**"); write("$b/**") } }
            val sandbox = ProcessSandbox.forPolicy(policy)
            assertTrue(sandbox.run(shWrite("x", a.resolve("f.txt"))).ok, "declared root A allowed")
            assertTrue(sandbox.run(shWrite("y", b.resolve("f.txt"))).ok, "declared root B allowed")
            assertFalse(sandbox.run(shWrite("z", outside.resolve("f.txt"))).ok, "undeclared path blocked")
            assertFalse(outside.resolve("f.txt").exists())
        } finally {
            a.deleteRecursively(); b.deleteRecursively(); outside.deleteRecursively()
        }
    }

    @Test fun `network is blocked by default and opened by allowNetwork`() {
        val python3 = (System.getenv("PATH") ?: "").split(File.pathSeparatorChar)
            .filter { it.isNotBlank() }
            .map { Path.of(it).resolve("python3") }
            .firstOrNull { Files.isExecutable(it) }
        Assumptions.assumeTrue(python3 != null, "python3 not on PATH — skipping live network probe")

        // A sandbox denial surfaces as PermissionError ([Errno 1] Operation not
        // permitted); a normal connect to a closed port is a different OSError. That
        // distinction is what proves the *sandbox* (not the network) did the blocking.
        val probe = """
            import socket
            s = socket.socket(); s.settimeout(2)
            try:
                s.connect(('127.0.0.1', 1)); print('CONNECTED')
            except PermissionError:
                print('BLOCKED')
            except OSError:
                print('ALLOWED')
        """.trimIndent()

        fun diag(r: SandboxResult) = "stdout='${r.stdout.trim()}' stderr='${r.stderr.trim()}'"

        val root = createTempDirectory("sbx-net").toRealPath()
        try {
            val blocked = ProcessSandbox.forWritableRoots(listOf(root), allowNetwork = false)
                .run(listOf(python3!!.toString(), "-c", probe))
            assertTrue("BLOCKED" in blocked.stdout, "deny-default must block the socket; ${diag(blocked)}")

            val allowed = ProcessSandbox.forWritableRoots(listOf(root), allowNetwork = true)
                .run(listOf(python3.toString(), "-c", probe))
            assertTrue(
                "ALLOWED" in allowed.stdout || "CONNECTED" in allowed.stdout,
                "allowNetwork must permit the socket; ${diag(allowed)}",
            )
        } finally {
            root.deleteRecursively()
        }
    }
}

/**
 * #2914 — `processTool` auto-sandboxes a subprocess tool from its declared policy.
 */
@EnabledOnOs(OS.MAC)
@Tag("mac_os_only")
@OptIn(ExperimentalPathApi::class)
class ProcessToolMacTest {

    private fun writePolicy(root: Path) = toolPolicy {
        risk = ToolRisk.MEDIUM
        filesystem { write("$root/**") }
    }

    private fun writer(root: Path) = processTool("writer", policy = writePolicy(root)) { args ->
        listOf("/bin/sh", "-c", "printf '%s' \"\$1\" > \"\$2\"", "sh", args["text"].toString(), args["path"].toString())
    }

    @Test fun `processTool write inside the declared policy root succeeds`() {
        val root = createTempDirectory("ptool-in").toRealPath()
        try {
            val target = root.resolve("out.txt")
            val res = writer(root).executor(mapOf("text" to "hello", "path" to target.toString())) as String
            assertFalse(res.startsWith("ERROR"), "in-policy write should succeed: $res")
            assertTrue(target.exists() && target.readText() == "hello")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun `processTool write outside the declared policy root is blocked`() {
        val root = createTempDirectory("ptool-root").toRealPath()
        val outside = createTempDirectory("ptool-out").toRealPath()
        try {
            val target = outside.resolve("escape.txt")
            val res = writer(root).executor(mapOf("text" to "nope", "path" to target.toString())) as String
            assertTrue(res.startsWith("ERROR"), "out-of-policy write should fail: $res")
            assertFalse(target.exists())
        } finally {
            root.deleteRecursively(); outside.deleteRecursively()
        }
    }

    @Test fun `processTool returns the command stdout on success`() {
        val root = createTempDirectory("ptool-stdout").toRealPath()
        try {
            val tool = processTool("echoer", policy = writePolicy(root)) { args ->
                listOf("/bin/echo", args["msg"].toString())
            }
            assertEquals("hello world", tool.executor(mapOf("msg" to "hello world")) as String)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun `processTool carries the declared policy and risk onto the ToolDef`() {
        val root = createTempDirectory("ptool-policy").toRealPath()
        try {
            val tool = processTool("writer", policy = writePolicy(root)) { emptyList() }
            assertEquals(ToolRisk.MEDIUM, tool.risk)
            assertTrue(tool.policy?.filesystem?.write?.globs?.any { "$root" in it } == true)
        } finally {
            root.deleteRecursively()
        }
    }
}

/**
 * #2892 — pure tests for the Linux bubblewrap argv. Run on every platform (no
 * subprocess), so the bwrap contract is pinned even on macOS / CI-without-bwrap.
 */
class BwrapArgsTest {

    private fun has3(args: List<String>, a: String, b: String, c: String) =
        args.windowed(3).any { it == listOf(a, b, c) }

    @Test fun `binds root-fs read-only, write roots read-write, unshares net by default`() {
        val args = ProcessSandbox.bwrapArgs(listOf(Path.of("/data/a"), Path.of("/data/b")))
        assertTrue(has3(args, "--ro-bind", "/", "/"), "whole fs read-only: $args")
        assertTrue(has3(args, "--bind", "/data/a", "/data/a"), "root A read-write: $args")
        assertTrue(has3(args, "--bind", "/data/b", "/data/b"), "root B read-write: $args")
        assertTrue("--unshare-net" in args, "network denied by default: $args")
    }

    @Test fun `allowNetwork keeps the network namespace`() {
        assertFalse("--unshare-net" in ProcessSandbox.bwrapArgs(listOf(Path.of("/data")), allowNetwork = true))
    }

    @Test fun `no write roots means no read-write bind (deny all writes)`() {
        assertFalse("--bind" in ProcessSandbox.bwrapArgs(emptyList()))
    }
}

/**
 * #2892 — pure tests for the firejail argv (the setuid fallback). Run anywhere.
 */
class FirejailArgsTest {

    @Test fun `mounts root read-only, write roots read-write, drops net by default`() {
        val args = ProcessSandbox.firejailArgs(listOf(Path.of("/data/a"), Path.of("/data/b")))
        assertTrue("--read-only=/" in args, "whole fs read-only: $args")
        assertTrue("--read-write=/data/a" in args, "root A read-write: $args")
        assertTrue("--read-write=/data/b" in args, "root B read-write: $args")
        assertTrue("--net=none" in args, "network dropped by default: $args")
        assertTrue("--noprofile" in args, "no default firejail profile: $args")
    }

    @Test fun `allowNetwork keeps networking`() {
        assertFalse("--net=none" in ProcessSandbox.firejailArgs(listOf(Path.of("/data")), allowNetwork = true))
    }

    @Test fun `no write roots means no read-write carve-out`() {
        assertFalse(ProcessSandbox.firejailArgs(emptyList()).any { it.startsWith("--read-write=") })
    }
}

/**
 * OS-gated integration: spawns the real Linux backend (`bwrap`, and `firejail` via
 * the forced-backend seam) and asserts kernel-level write confinement. Tagged
 * `linux_only` + `@EnabledOnOs(OS.LINUX)`, so it auto-skips on macOS; CI runs it on
 * a native Ubuntu runner. The `assume*Usable` probes skip cleanly when the tool is
 * absent or can't build a sandbox on the host.
 */
@EnabledOnOs(OS.LINUX)
@Tag("linux_only")
@OptIn(ExperimentalPathApi::class)
class ProcessSandboxLinuxTest {

    private fun shWrite(text: String, target: Path): List<String> =
        listOf("/bin/sh", "-c", "printf '%s' \"\$1\" > \"\$2\"", "sh", text, target.toString())

    /**
     * Skip cleanly when bwrap is absent *or* present-but-unusable. On a host that
     * restricts unprivileged user namespaces (e.g. Ubuntu 24.04's
     * `kernel.apparmor_restrict_unprivileged_userns=1`) with a non-setuid
     * `bubblewrap`, bwrap cannot create its namespace — it prints `bwrap: ...` and
     * every command returns non-zero. That is an *environment* limitation, not a
     * policy verdict, so a confinement test can't be meaningfully run; skip it
     * rather than report a false failure. A trivial `/bin/true` probe distinguishes
     * "the kernel won't let bwrap start" (skip) from "bwrap runs but my args are
     * wrong" (the real assertions below still execute and fail).
     */
    private fun assumeBwrapUsable() {
        Assumptions.assumeTrue(ProcessSandbox.isSupported(), "bwrap not installed")
        val probeRoot = createTempDirectory("sbx-probe").toRealPath()
        try {
            val probe = ProcessSandbox(probeRoot).run(listOf("/bin/true"))
            Assumptions.assumeTrue(
                probe.ok,
                "bwrap is installed but cannot create a sandbox here (likely restricted " +
                    "unprivileged user namespaces — see kernel.apparmor_restrict_unprivileged_userns); " +
                    "exit=${probe.exitCode} stderr=${probe.stderr.trim()}",
            )
        } finally {
            probeRoot.deleteRecursively()
        }
    }

    @Test fun `bwrap is detected as a supported backend`() {
        Assumptions.assumeTrue(ProcessSandbox.isSupported(), "bwrap not installed")
        assertTrue(ProcessSandbox.isSupported())
    }

    @Test fun `write inside the sandboxed folder succeeds`() {
        assumeBwrapUsable()
        val root = createTempDirectory("sbx-lin-in").toRealPath()
        try {
            val target = root.resolve("note.txt")
            val res = ProcessSandbox(root).run(shWrite("hello bwrap", target))
            assertTrue(res.ok, "expected success, got exit=${res.exitCode} stderr=${res.stderr}")
            assertTrue(target.exists() && target.readText() == "hello bwrap")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun `write outside the sandboxed folder is blocked by bwrap`() {
        assumeBwrapUsable()
        val root = createTempDirectory("sbx-lin-root").toRealPath()
        val outside = createTempDirectory("sbx-lin-out").toRealPath()
        try {
            val target = outside.resolve("escape.txt")
            val res = ProcessSandbox(root).run(shWrite("nope", target))
            assertFalse(res.ok, "out-of-folder write must fail; exit=${res.exitCode}")
            assertFalse(target.exists(), "no file may be created outside the sandboxed folder")
        } finally {
            root.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    // --- firejail (the setuid fallback), forced via the internal backend seam so
    // it is exercised even on CI where bwrap is also installed and would win. ---

    private fun assumeFirejailUsable() {
        val probeRoot = createTempDirectory("fj-probe").toRealPath()
        try {
            val probe = runCatching {
                ProcessSandbox(probeRoot).runWithBackend(ProcessSandbox.Backend.FIREJAIL, listOf("/bin/true"))
            }.getOrNull()
            Assumptions.assumeTrue(
                probe != null && probe.ok,
                "firejail not installed or cannot sandbox here: " +
                    (probe?.let { "exit=${it.exitCode} stderr=${it.stderr.trim()}" } ?: "firejail binary not found"),
            )
        } finally {
            probeRoot.deleteRecursively()
        }
    }

    @Test fun `firejail confines a write inside the folder`() {
        assumeFirejailUsable()
        val root = createTempDirectory("fj-in").toRealPath()
        try {
            val target = root.resolve("note.txt")
            val res = ProcessSandbox(root)
                .runWithBackend(ProcessSandbox.Backend.FIREJAIL, shWrite("hello firejail", target))
            assertTrue(res.ok, "expected success, got exit=${res.exitCode} stderr=${res.stderr}")
            assertTrue(target.exists() && target.readText() == "hello firejail")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun `firejail blocks a write outside the folder`() {
        assumeFirejailUsable()
        val root = createTempDirectory("fj-root").toRealPath()
        val outside = createTempDirectory("fj-out").toRealPath()
        try {
            val target = outside.resolve("escape.txt")
            val res = ProcessSandbox(root)
                .runWithBackend(ProcessSandbox.Backend.FIREJAIL, shWrite("nope", target))
            assertFalse(res.ok, "out-of-folder write must fail; exit=${res.exitCode} stderr=${res.stderr}")
            assertFalse(target.exists(), "no file may be created outside the sandboxed folder")
        } finally {
            root.deleteRecursively()
            outside.deleteRecursively()
        }
    }
}

/**
 * #2892 — the no-backend fallback. When no OS sandbox tool is present, [run] runs
 * the command via a plain `ProcessBuilder` and warns loudly that it is UNCONFINED.
 * Forced via `Backend.NONE`, so it is testable on any POSIX host regardless of
 * which sandbox tools are installed.
 */
@EnabledOnOs(OS.MAC, OS.LINUX)
@OptIn(ExperimentalPathApi::class)
class ProcessSandboxFallbackTest {

    @Test fun `no backend runs the command unconfined and warns loudly`() {
        val root = createTempDirectory("nofb").toRealPath()
        val originalErr = System.err
        val captured = java.io.ByteArrayOutputStream()
        try {
            System.setErr(java.io.PrintStream(captured, true, "UTF-8"))
            val res = ProcessSandbox(root)
                .runWithBackend(ProcessSandbox.Backend.NONE, listOf("/bin/echo", "hi"))
            assertTrue(res.ok, "plain ProcessBuilder should run the command: exit=${res.exitCode}")
            assertEquals("hi", res.stdout.trim())
        } finally {
            System.setErr(originalErr)
            root.deleteRecursively()
        }
        val warning = captured.toString("UTF-8")
        assertTrue("UNCONFINED" in warning, "must warn the process is unconfined: $warning")
    }
}
