package agents_engine.core

import java.nio.file.FileSystems
import java.nio.file.Path

/**
 * Layer 1 of #1916 — the in-JVM filesystem policy gate.
 *
 * Given a tool's *declared* [ToolPolicy] and the arguments of a pending call,
 * decides whether the call may proceed. Enforcement is **opt-in by declaration**:
 * a tool whose filesystem stance is `Unspecified`/`Unspecified` is never gated, so
 * existing tools are unaffected.
 *
 * The gate inspects arguments rather than the process: any **absolute** filesystem
 * path passed as a string argument must fall inside the union of the declared
 * `read` + `write` globs (after `..`/`.` normalization, so traversal cannot escape
 * a glob). The first out-of-policy path denies the call.
 *
 * Deliberate Layer-1 limitations (covered by the Layer-2 OS sandbox, sibling issues):
 *  - Only **absolute** path arguments are gated. Relative paths are not — the JVM
 *    has no reliable, side-effect-free way to bind a lambda's CWD, and treating
 *    every slash-bearing string as a path would false-deny ordinary content.
 *  - **network** and **environment** sub-policies are not enforced here: a plain
 *    in-process lambda can open a socket or read an env var with no interception
 *    point (no SecurityManager in modern JDKs).
 */
internal object ToolPolicyEnforcer {

    fun evaluate(policy: ToolPolicy?, args: Map<String, Any?>): Decision<Map<String, Any?>> {
        val fs = policy?.filesystem ?: return Decision.Proceed

        // Opt-in by declaration: enforce only when a filesystem stance was declared.
        if (!fs.read.isDeclared && !fs.write.isDeclared) return Decision.Proceed

        val allowed = fs.read.globs + fs.write.globs
        for ((_, value) in args) {
            val path = (value as? String)?.let(::absolutePathOrNull) ?: continue
            if (allowed.none { matches(it, path) }) {
                return Decision.Deny(
                    "path '$path' outside declared filesystem policy $allowed",
                )
            }
        }
        return Decision.Proceed
    }

    private val ToolFilesystemAccess.isDeclared: Boolean
        get() = this != ToolFilesystemAccess.Unspecified

    /** Normalized absolute path for a string that *is* an absolute path; else null. */
    private fun absolutePathOrNull(value: String): String? =
        runCatching {
            val p = Path.of(value)
            if (p.isAbsolute) p.normalize().toString() else null
        }.getOrNull()

    private fun matches(glob: String, path: String): Boolean =
        runCatching {
            FileSystems.getDefault().getPathMatcher("glob:$glob").matches(Path.of(path))
        }.getOrDefault(false)
}
