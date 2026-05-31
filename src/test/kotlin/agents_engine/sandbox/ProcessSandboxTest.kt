package agents_engine.sandbox

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
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
}
