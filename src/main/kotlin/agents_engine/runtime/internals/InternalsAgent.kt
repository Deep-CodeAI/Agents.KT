package agents_engine.runtime.internals

import agents_engine.core.Agent
import agents_engine.core.agent
import agents_engine.core.loadResource
import java.io.File
import java.net.JarURLConnection

/**
 * #1837 — Agents.KT InternalsAgent: a self-hosting docs agent whose skills
 * correspond to source files in the framework. Exposed via
 * `McpServer.from(buildInternalsAgent())` so IDE-side AI agents (Cursor,
 * Claude Desktop) can query the framework's own structure as tools.
 *
 * **Single source of truth: the adjunct `.md`.** Each adjunct under
 * `src/main/resources/internals-agent/<package>/<File>.md` begins with a
 * YAML-style frontmatter block:
 *
 * ```markdown
 * ---
 * description: <one-line tool description shown to the IDE LLM>
 * ---
 *
 * # <heading>
 * <body returned as tool result>
 * ```
 *
 * [buildInternalsAgent] scans the classpath for every `.md` under
 * `internals-agent/`, derives the skill name from the path
 * (`internals-agent/core/Agent.md` → `core_agent_kt`), reads the
 * `description:` line from frontmatter, and registers one skill per file.
 * Adding a new source file means dropping in one .md — no code edit.
 *
 * **No model {}.** The IDE's LLM (not ours) does the reasoning. Each
 * skill is a pure data fetch via `loadResource(path)`. The framework-side
 * agent has no model configured.
 *
 * Running locally: `Main.kt` exposes this over MCP on port 8765.
 */
fun buildInternalsAgent(): Agent<String, String> = agent<String, String>("agents-kt-internals") {
    skills {
        scanInternalsAdjuncts().forEach { adjunct ->
            skill<String, String>(
                name = adjunct.skillName,
                description = adjunct.description,
            ) {
                implementedBy { _ -> loadResource(adjunct.resourcePath) }
            }
        }
    }
}

private const val ADJUNCT_PREFIX = "internals-agent/"

private data class InternalsAdjunct(
    /** Classpath-relative resource path, e.g. `internals-agent/core/Agent.md`. */
    val resourcePath: String,
    /** Skill name derived from [resourcePath], e.g. `core_agent_kt`. */
    val skillName: String,
    /** One-line description read from the .md's `description:` frontmatter line. */
    val description: String,
)

/**
 * Scans the classpath for every `.md` under [ADJUNCT_PREFIX], returns one
 * [InternalsAdjunct] per file sorted by path (so skill registration order
 * is stable across runs).
 */
private fun scanInternalsAdjuncts(): List<InternalsAdjunct> =
    listAdjunctPaths().sorted().map { path ->
        InternalsAdjunct(
            resourcePath = path,
            skillName = deriveSkillName(path),
            description = readFrontmatterDescription(path),
        )
    }

/**
 * Path → skill name mapping. The reverse derivation (skill name → path)
 * is not lossless (case is dropped), so the path is authoritative —
 * the skill name is derived from it, not the other way around.
 *
 * Examples:
 * - `internals-agent/core/Agent.md`                          → `core_agent_kt`
 * - `internals-agent/composition/branch/BranchBuilder.md`    → `composition_branch_branchbuilder_kt`
 * - `internals-agent/ksp/AgentsKtSymbolProcessor.md`         → `ksp_agentsktsymbolprocessor_kt`
 */
private fun deriveSkillName(resourcePath: String): String =
    resourcePath.removePrefix(ADJUNCT_PREFIX).removeSuffix(".md")
        .replace('/', '_').lowercase() + "_kt"

/**
 * Reads the `description:` line from the .md's YAML-style frontmatter.
 * Format:
 *
 * ```
 * ---
 * description: <text>
 * ---
 *
 * <body>
 * ```
 *
 * Fails fast at agent construction if the frontmatter is missing,
 * malformed, or has no `description:` line.
 */
private fun readFrontmatterDescription(resourcePath: String): String {
    val content = loadResource(resourcePath)
    require(content.startsWith("---\n")) {
        "$resourcePath is missing the leading `---` frontmatter block."
    }
    val end = content.indexOf("\n---\n", startIndex = 4)
    require(end >= 0) {
        "$resourcePath has an unterminated frontmatter block (no closing `---`)."
    }
    val frontmatter = content.substring(4, end)
    val line = frontmatter.lineSequence().firstOrNull { it.startsWith("description:") }
        ?: error("$resourcePath frontmatter is missing a `description:` line.")
    return line.removePrefix("description:").trim()
}

/**
 * Enumerates `.md` files under [ADJUNCT_PREFIX] on the runtime classpath.
 * Handles both file-system layouts (development / IDE runs) and JAR
 * layouts (production / shaded distributions).
 */
private fun listAdjunctPaths(): List<String> {
    val cl = Thread.currentThread().contextClassLoader
        ?: ::listAdjunctPaths.javaClass.classLoader
    val url = cl.getResource(ADJUNCT_PREFIX.removeSuffix("/"))
        ?: error(
            "Classpath resource `$ADJUNCT_PREFIX` not found. " +
                "Adjuncts must live under src/main/resources/$ADJUNCT_PREFIX.",
        )
    return when (url.protocol) {
        "file" -> {
            val root = File(url.toURI())
            root.walkTopDown()
                .filter { it.isFile && it.extension == "md" }
                .map { ADJUNCT_PREFIX + it.relativeTo(root).invariantSeparatorsPath }
                .toList()
        }
        "jar" -> {
            val conn = url.openConnection() as JarURLConnection
            conn.jarFile.entries().asSequence()
                .filter {
                    !it.isDirectory &&
                        it.name.startsWith(ADJUNCT_PREFIX) &&
                        it.name.endsWith(".md")
                }
                .map { it.name }
                .toList()
        }
        else -> error("Unsupported classpath resource protocol: ${url.protocol}")
    }
}
