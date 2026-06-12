package agents_engine.core

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path

/**
 * #2883/#2889 — default [ToolEnvironment]: every operation is checked
 * against the declared [ToolPolicy] before it runs (paths normalized so
 * `..` cannot escape a glob). A tool with no declared policy gets a
 * fail-closed environment — the env ABI is opt-in by declaration, the
 * mirror image of Layer 1's opt-in gating of raw arguments.
 */
internal class JvmToolEnvironment(
    private val toolName: String,
    private val policy: ToolPolicy?,
) : ToolEnvironment {

    override fun readText(path: String): String {
        val normalized = checkFs(path, policy?.filesystem?.read?.globs.orEmpty(), "readText")
        return Files.readString(Path.of(normalized))
    }

    override fun writeText(path: String, content: String) {
        val normalized = checkFs(path, policy?.filesystem?.write?.globs.orEmpty(), "writeText")
        Files.writeString(Path.of(normalized), content)
    }

    override fun env(name: String): String? {
        val allowed = (policy?.environment as? ToolEnvironmentPolicy.Vars)?.variables.orEmpty()
        if (name !in allowed) {
            throw ToolPolicyViolation(
                toolName,
                "env",
                name,
                "variable not in declared environment allow-list $allowed",
            )
        }
        return System.getenv(name)
    }

    private fun checkFs(path: String, globs: List<String>, operation: String): String {
        val normalized = Path.of(path).toAbsolutePath().normalize().toString()
        if (globs.none { matches(it, normalized) }) {
            throw ToolPolicyViolation(
                toolName,
                operation,
                normalized,
                "path outside declared globs $globs",
            )
        }
        return normalized
    }

    private fun matches(glob: String, path: String): Boolean =
        runCatching {
            FileSystems.getDefault().getPathMatcher("glob:$glob").matches(Path.of(path))
        }.getOrDefault(false)
}
