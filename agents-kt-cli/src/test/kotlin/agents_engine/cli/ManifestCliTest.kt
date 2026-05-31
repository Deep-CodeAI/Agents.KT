package agents_engine.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.ExperimentalPathApi
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #1923 — unit tests for the manifest CLI. [ManifestCli.run] takes its streams, so we
 * drive every command with captured stdout/stderr and assert the exit-code contract
 * (0 ok · 1 verify findings · 2 usage · 3 runtime). The `generate`/`verify --entrypoint`
 * paths exercise the real reflective loader against [StrictEntrypoint]/[WidenedEntrypoint].
 */
@OptIn(ExperimentalPathApi::class)
class ManifestCliTest {

    private val strictFqn = "agents_engine.cli.StrictEntrypoint"
    private val widenedFqn = "agents_engine.cli.WidenedEntrypoint"

    private class Run(val code: Int, val out: String, val err: String)

    private fun cli(vararg args: String): Run {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = ManifestCli.run(
            args.toList(),
            PrintStream(out, true, "UTF-8"),
            PrintStream(err, true, "UTF-8"),
        )
        return Run(code, out.toString("UTF-8"), err.toString("UTF-8"))
    }

    private fun strictJson(): String = StrictEntrypoint.permissionManifest().toJson()
    private fun widenedJson(): String = WidenedEntrypoint.permissionManifest().toJson()

    // --- dispatch / usage ----------------------------------------------------

    @Test fun `version exits 0 and prints a version`() {
        val r = cli("--version")
        assertEquals(0, r.code)
        assertTrue(r.out.trim().isNotEmpty(), "should print a version line")
    }

    @Test fun `help exits 0 and lists the commands`() {
        val r = cli("--help")
        assertEquals(0, r.code)
        assertContains(r.out, "generate")
        assertContains(r.out, "inspect")
        assertContains(r.out, "verify")
    }

    @Test fun `no args is a usage error (exit 2)`() {
        assertEquals(2, cli().code)
    }

    @Test fun `unknown command is a usage error (exit 2)`() {
        val r = cli("frobnicate")
        assertEquals(2, r.code)
        assertContains(r.err, "unknown command")
    }

    // --- generate ------------------------------------------------------------

    @Test fun `generate from an entrypoint prints a json manifest`() {
        val r = cli("generate", "--entrypoint", strictFqn)
        assertEquals(0, r.code, "stderr=${r.err}")
        assertContains(r.out, "agentsKtManifestVersion")
        assertContains(r.out, "manifestSha256")
    }

    @Test fun `generate --format yaml prints yaml`() {
        val r = cli("generate", "--entrypoint", strictFqn, "--format", "yaml")
        assertEquals(0, r.code, "stderr=${r.err}")
        assertContains(r.out, "agentsKtManifestVersion:")
    }

    @Test fun `generate --out writes the manifest to a file`() {
        val dir = createTempDirectory("cli-gen")
        try {
            val target = dir.resolve("permissions.json").toString()
            val r = cli("generate", "--entrypoint", strictFqn, "--out", target)
            assertEquals(0, r.code, "stderr=${r.err}")
            val written = Files.readString(dir.resolve("permissions.json"))
            assertContains(written, "manifestSha256")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun `generate without --entrypoint is a usage error`() {
        assertEquals(2, cli("generate").code)
    }

    @Test fun `generate with an unknown entrypoint is a runtime error`() {
        val r = cli("generate", "--entrypoint", "no.such.Class")
        assertEquals(3, r.code)
        assertContains(r.err, "error:")
    }

    // --- inspect -------------------------------------------------------------

    @Test fun `inspect round-trips a manifest file`() {
        val dir = createTempDirectory("cli-inspect")
        try {
            val file = dir.resolve("m.json")
            Files.writeString(file, strictJson())
            val r = cli("inspect", file.toString())
            assertEquals(0, r.code, "stderr=${r.err}")
            assertContains(r.out, "manifestSha256")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun `inspect a missing file is a runtime error`() {
        val r = cli("inspect", "/no/such/manifest.json")
        assertEquals(3, r.code)
        assertContains(r.err, "not found")
    }

    @Test fun `inspect with no file is a usage error`() {
        assertEquals(2, cli("inspect").code)
    }

    // --- verify --------------------------------------------------------------

    @Test fun `verify identical current vs baseline is OK (exit 0)`() {
        val dir = createTempDirectory("cli-verify-ok")
        try {
            val base = dir.resolve("base.json"); Files.writeString(base, strictJson())
            val cur = dir.resolve("cur.json"); Files.writeString(cur, strictJson())
            val r = cli("verify", "--current", cur.toString(), "--baseline", base.toString())
            assertEquals(0, r.code, "stderr=${r.err}")
            assertContains(r.out, "OK")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun `verify a widened manifest vs baseline fails with findings (exit 1)`() {
        val dir = createTempDirectory("cli-verify-fail")
        try {
            val base = dir.resolve("base.json"); Files.writeString(base, strictJson())
            val cur = dir.resolve("cur.json"); Files.writeString(cur, widenedJson())
            val r = cli("verify", "--current", cur.toString(), "--baseline", base.toString())
            assertEquals(1, r.code, "out=${r.out}")
            assertContains(r.err, "widen")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun `verify --entrypoint against a baseline file detects widening`() {
        val dir = createTempDirectory("cli-verify-entry")
        try {
            val base = dir.resolve("base.json"); Files.writeString(base, strictJson())
            val r = cli("verify", "--entrypoint", widenedFqn, "--baseline", base.toString())
            assertEquals(1, r.code, "out=${r.out} err=${r.err}")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun `verify without a baseline is a usage error`() {
        assertEquals(2, cli("verify", "--current", "/tmp/whatever.json").code)
    }
}
