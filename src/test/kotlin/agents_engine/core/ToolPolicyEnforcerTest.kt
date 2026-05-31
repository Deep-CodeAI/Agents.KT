package agents_engine.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * #2890 (Layer 1 of #1916) — pure-logic matrix for the in-JVM filesystem policy
 * gate. The gate inspects tool *arguments*: any absolute filesystem-path argument
 * must fall inside the tool's declared read∪write globs, otherwise the call is
 * denied before its executor runs.
 *
 * Design contract under test:
 *  - Enforcement is **opt-in by declaration**: a tool whose filesystem stance is
 *    `Unspecified`/`Unspecified` is never gated (backward-compat).
 *  - Candidate path args are **absolute paths only** (relative strings and ordinary
 *    content are ignored — documented Layer-1 limitation; relative-path precision
 *    and network/env isolation are Layer 2 / OS sandbox).
 *  - Paths are **normalized** before matching, so `..` traversal cannot escape a glob.
 *  - `None` stance ⇒ empty allow-set ⇒ any absolute path arg is denied.
 */
class ToolPolicyEnforcerTest {

    // Absolute roots — these are never created on disk; the gate is pure path logic.
    private val base = if (isWindows) "C:\\base" else "/base"
    private fun abs(vararg seg: String): String =
        (listOf(base) + seg).joinToString(sep)

    private val sep get() = java.io.File.separator
    private val isWindows get() = sep == "\\"

    private fun fsPolicy(
        read: ToolFilesystemAccess = ToolFilesystemAccess.Unspecified,
        write: ToolFilesystemAccess = ToolFilesystemAccess.Unspecified,
        risk: ToolRisk = ToolRisk.MEDIUM,
    ) = ToolPolicy(risk = risk, filesystem = ToolFilesystemPolicy(read = read, write = write))

    private fun globs(vararg g: String) = ToolFilesystemAccess.Globs(g.toList())

    // allowed-subtree glob: <base>/allowed + recursive wildcard
    private val allowedWriteGlob get() = abs("allowed") + sep + "**"
    private val insideAllowed get() = abs("allowed", "notes", "n.txt")
    private val outsideAllowed get() = abs("secret", "vault", "s.txt")

    private fun evaluate(policy: ToolPolicy?, args: Map<String, Any?>) =
        ToolPolicyEnforcer.evaluate(policy, args)

    // NOTE: assertProceed/assertDeny return Unit on purpose. A `@Test fun x() =
    // assertProceed(...)` whose body returns a non-Unit value (assertIs leaks its
    // result) is silently dropped by the JUnit5 platform. Use denyOf() when the
    // Deny.reason needs inspecting.
    private fun assertProceed(policy: ToolPolicy?, args: Map<String, Any?>) {
        assertIs<Decision.Proceed>(evaluate(policy, args), "expected Proceed for args=$args")
    }

    private fun assertDeny(policy: ToolPolicy?, args: Map<String, Any?>) {
        assertIs<Decision.Deny>(evaluate(policy, args), "expected Deny for args=$args")
    }

    private fun denyOf(policy: ToolPolicy?, args: Map<String, Any?>): Decision.Deny =
        assertIs<Decision.Deny>(evaluate(policy, args), "expected Deny for args=$args")

    // ---- undeclared / null: never gated (backward-compat) --------------------

    @Test fun `null policy proceeds even with an out-of-nowhere absolute path`() =
        assertProceed(null, mapOf("path" to outsideAllowed))

    @Test fun `unspecified filesystem stance is not enforced`() =
        // Declares only risk; filesystem read+write both Unspecified ⇒ inert.
        assertProceed(fsPolicy(), mapOf("path" to outsideAllowed))

    @Test fun `network or env declared but filesystem unspecified is not gated by Layer 1`() {
        val p = ToolPolicy(
            risk = ToolRisk.HIGH,
            network = ToolNetworkPolicy.DenyAll,
            environment = ToolEnvironmentPolicy.DenyAll,
        )
        // Layer 1 only enforces filesystem; network/env need the OS sandbox (Layer 2).
        assertProceed(p, mapOf("path" to outsideAllowed))
    }

    // ---- write Globs stance ---------------------------------------------------

    @Test fun `write glob allows an in-policy absolute path`() =
        assertProceed(fsPolicy(write = globs(allowedWriteGlob)), mapOf("path" to insideAllowed))

    @Test fun `write glob denies an out-of-policy absolute path`() {
        val deny = denyOf(fsPolicy(write = globs(allowedWriteGlob)), mapOf("path" to outsideAllowed))
        assertTrue(outsideAllowed in deny.reason, "reason names the offending path: ${deny.reason}")
        assertTrue("policy" in deny.reason.lowercase(), "reason explains it is a policy denial: ${deny.reason}")
    }

    @Test fun `multiple path args all in-policy proceed`() =
        assertProceed(
            fsPolicy(write = globs(allowedWriteGlob)),
            mapOf("src" to insideAllowed, "dst" to abs("allowed", "out", "o.txt")),
        )

    @Test fun `multiple path args with one out-of-policy deny`() {
        val deny = denyOf(
            fsPolicy(write = globs(allowedWriteGlob)),
            mapOf("src" to insideAllowed, "dst" to outsideAllowed),
        )
        assertTrue(outsideAllowed in deny.reason, "reason names the offending path, not the allowed one")
    }

    @Test fun `declared stance with no path-shaped args proceeds`() =
        assertProceed(
            fsPolicy(write = globs(allowedWriteGlob)),
            mapOf("mode" to "fast", "count" to 3, "note" to "hello world"),
        )

    // ---- read stance & union --------------------------------------------------

    @Test fun `read glob allows in-policy and denies out-of-policy path`() {
        val p = fsPolicy(read = globs(abs("data") + sep + "**"))
        assertProceed(p, mapOf("path" to abs("data", "x.csv")))
        assertDeny(p, mapOf("path" to outsideAllowed))
    }

    @Test fun `allow-set is the union of read and write globs`() {
        val p = fsPolicy(
            read = globs(abs("data") + sep + "**"),
            write = globs(allowedWriteGlob),
        )
        assertProceed(p, mapOf("in" to abs("data", "x.csv"), "out" to insideAllowed))
        assertDeny(p, mapOf("path" to outsideAllowed))
    }

    // ---- None stance: deny any path ------------------------------------------

    @Test fun `write None denies any absolute path arg`() =
        assertDeny(fsPolicy(write = ToolFilesystemAccess.None), mapOf("path" to insideAllowed))

    @Test fun `read None and write None denies any absolute path arg`() =
        assertDeny(
            fsPolicy(read = ToolFilesystemAccess.None, write = ToolFilesystemAccess.None),
            mapOf("path" to insideAllowed),
        )

    @Test fun `declared stance via read None still gates write-glob-less paths`() =
        // read=None is a declared stance even though write is Unspecified ⇒ allow-set empty ⇒ deny.
        assertDeny(fsPolicy(read = ToolFilesystemAccess.None), mapOf("path" to insideAllowed))

    @Test fun `empty Globs list is a declared-but-allows-nothing stance and denies`() =
        assertDeny(fsPolicy(write = ToolFilesystemAccess.Globs(emptyList())), mapOf("path" to insideAllowed))

    // ---- security: path traversal cannot escape a glob -----------------------

    @Test fun `normalized in-policy traversal still proceeds`() =
        assertProceed(
            fsPolicy(write = globs(allowedWriteGlob)),
            // /base/allowed/../allowed/n.txt  ->  /base/allowed/n.txt
            mapOf("path" to abs("allowed", "..", "allowed", "n.txt")),
        )

    @Test fun `traversal escaping the glob is denied after normalization`() =
        assertDeny(
            fsPolicy(write = globs(allowedWriteGlob)),
            // /base/allowed/../secret/s.txt  ->  /base/secret/s.txt  (escapes allowed/**)
            mapOf("path" to abs("allowed", "..", "secret", "s.txt")),
        )

    // ---- heuristic edges: absolute-only, robust to junk ----------------------

    @Test fun `relative path arg is not gated in Layer 1`() =
        // Documented limitation: relative paths are not absolute ⇒ ignored by the heuristic.
        assertProceed(
            fsPolicy(write = globs(allowedWriteGlob)),
            mapOf("path" to "secret${sep}s.txt"),
        )

    @Test fun `an absolute-path-shaped value in any arg key is conservatively gated`() =
        // The heuristic keys on shape, not arg name: an absolute string in "content"
        // is treated as a path. Conservative-by-design (documented).
        assertDeny(
            fsPolicy(write = globs(allowedWriteGlob)),
            mapOf("content" to outsideAllowed),
        )

    @Test fun `non-string and null args are ignored`() =
        assertProceed(
            fsPolicy(write = globs(allowedWriteGlob)),
            mapOf("path" to insideAllowed, "count" to 42, "flag" to true, "missing" to null, "nested" to listOf(1, 2)),
        )

    @Test fun `un-parseable path-like string does not crash and is ignored`() =
        // NUL byte ⇒ InvalidPathException ⇒ runCatching false ⇒ not treated as a path.
        assertProceed(fsPolicy(write = globs(allowedWriteGlob)), mapOf("weird" to (abs("x") + "\u0000bad")))

    @Test fun `deny reason includes the declared globs for auditability`() {
        val deny = denyOf(fsPolicy(write = globs(allowedWriteGlob)), mapOf("path" to outsideAllowed))
        assertTrue(allowedWriteGlob in deny.reason, "reason should surface the declared globs: ${deny.reason}")
    }

    @Test fun `empty args always proceed regardless of stance`() {
        assertProceed(fsPolicy(write = ToolFilesystemAccess.None), emptyMap())
        assertProceed(fsPolicy(write = globs(allowedWriteGlob)), emptyMap())
    }

    @Test fun `first offending path is reported deterministically`() {
        // Two out-of-policy paths: reason should reference one of them (the first encountered).
        val deny = denyOf(
            fsPolicy(write = globs(allowedWriteGlob)),
            linkedMapOf("a" to outsideAllowed, "b" to abs("other", "z.txt")),
        )
        assertEquals(true, outsideAllowed in deny.reason || abs("other", "z.txt") in deny.reason)
    }
}
