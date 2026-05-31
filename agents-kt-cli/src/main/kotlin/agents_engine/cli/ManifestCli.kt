package agents_engine.cli

import agents_engine.manifest.ManifestEntrypointLoader
import agents_engine.manifest.PermissionManifest
import java.io.File
import java.io.PrintStream
import kotlin.system.exitProcess

/** Process entrypoint — delegates to [ManifestCli.run] and maps the result to an exit code. */
fun main(args: Array<String>): Unit = exitProcess(ManifestCli.run(args.toList(), System.out, System.err))

/**
 * Standalone CLI (#1923) — the "externally" half of 0.7.0 ("boundaries you can enforce
 * externally"). Generates, inspects, and verifies the deterministic Agents.KT
 * **permission manifest** from a binary, so non-Gradle consumers (CI gates, ops,
 * regulators) can reach what was previously only a Gradle task. It wraps the same
 * `agents-kt-manifest` logic the Gradle plugin uses ([ManifestEntrypointLoader] +
 * [PermissionManifest]), so a build and a CLI produce byte-identical manifests.
 *
 * Commands:
 * - `generate --entrypoint <FQN> [--classpath <a:b>] [--format json|yaml] [--out <file>]`
 * - `inspect <manifest.json> [--format json|yaml]`
 * - `verify (--entrypoint <FQN> [--classpath <a:b>] | --current <file>) --baseline <file>`
 *
 * Exit codes: `0` ok · `1` verify findings (policy widened) · `2` usage error ·
 * `3` runtime error (load/parse/IO). [run] takes its streams so it is unit-testable
 * without touching the real process streams.
 */
object ManifestCli {

    const val VERSION_FALLBACK: String = "dev"

    private const val EXIT_OK = 0
    private const val EXIT_FINDINGS = 1
    private const val EXIT_USAGE = 2
    private const val EXIT_RUNTIME = 3

    private val version: String
        get() = ManifestCli::class.java.`package`?.implementationVersion ?: VERSION_FALLBACK

    fun run(args: List<String>, out: PrintStream, err: PrintStream): Int {
        val command = args.firstOrNull()
        return when (command) {
            null -> { printHelp(err); EXIT_USAGE }
            "-h", "--help", "help" -> { printHelp(out); EXIT_OK }
            "-V", "--version", "version" -> { out.println(version); EXIT_OK }
            "generate" -> generate(parse(args.drop(1)), out, err)
            "inspect" -> inspect(parse(args.drop(1)), out, err)
            "verify" -> verify(parse(args.drop(1)), out, err)
            else -> { err.println("error: unknown command '$command'"); printHelp(err); EXIT_USAGE }
        }
    }

    private fun generate(o: Parsed, out: PrintStream, err: PrintStream): Int {
        val entrypoint = o["--entrypoint"] ?: return usage(err, "generate requires --entrypoint <FQN>")
        val format = o["--format"] ?: "json"
        val manifest = loadEntrypoint(entrypoint, o["--classpath"], err) ?: return EXIT_RUNTIME
        val text = render(manifest, format) ?: return usage(err, "unknown --format '$format' (use json|yaml)")
        val outFile = o["--out"]
        if (outFile != null) {
            File(outFile).also { it.parentFile?.mkdirs() }.writeText(text + "\n")
            out.println("wrote $outFile (sha256 ${manifest.sha256})")
        } else {
            out.println(text)
        }
        return EXIT_OK
    }

    private fun inspect(o: Parsed, out: PrintStream, err: PrintStream): Int {
        val path = o.positionals.firstOrNull() ?: o["--input"]
            ?: return usage(err, "inspect requires a manifest file: inspect <manifest.json>")
        val manifest = readManifest(path, err) ?: return EXIT_RUNTIME
        val text = render(manifest, o["--format"] ?: "json") ?: return usage(err, "unknown --format")
        out.println(text)
        return EXIT_OK
    }

    private fun verify(o: Parsed, out: PrintStream, err: PrintStream): Int {
        val baselinePath = o["--baseline"] ?: return usage(err, "verify requires --baseline <file>")
        val baseline = readManifest(baselinePath, err, label = "baseline") ?: return EXIT_RUNTIME

        val current = when {
            o["--current"] != null -> readManifest(o["--current"]!!, err, label = "current") ?: return EXIT_RUNTIME
            o["--entrypoint"] != null -> loadEntrypoint(o["--entrypoint"]!!, o["--classpath"], err) ?: return EXIT_RUNTIME
            else -> return usage(err, "verify requires --current <file> or --entrypoint <FQN>")
        }

        val result = current.verifyAgainst(baseline)
        if (result.ok) {
            out.println("OK: permission manifest does not widen any boundary vs baseline")
            return EXIT_OK
        }
        err.println("FAIL: permission manifest widens boundaries vs baseline:")
        result.findings.forEach { err.println("  - [${it.severity}] ${it.code} ${it.path}: ${it.message}") }
        return EXIT_FINDINGS
    }

    // --- helpers -------------------------------------------------------------

    private fun loadEntrypoint(fqn: String, classpath: String?, err: PrintStream): PermissionManifest? =
        runCatching { ManifestEntrypointLoader.load(fqn, classpathFiles(classpath)) }
            .onFailure { err.println("error: could not load manifest from '$fqn': ${it.message}") }
            .getOrNull()

    private fun readManifest(path: String, err: PrintStream, label: String = "manifest"): PermissionManifest? {
        val file = File(path)
        if (!file.isFile) {
            err.println("error: $label file not found: $path")
            return null
        }
        return runCatching { PermissionManifest.fromJson(file.readText()) }
            .onFailure { err.println("error: invalid $label manifest '$path': ${it.message}") }
            .getOrNull()
    }

    private fun render(manifest: PermissionManifest, format: String): String? = when (format.lowercase()) {
        "json" -> manifest.toJson()
        "yaml", "yml" -> manifest.toYaml()
        else -> null
    }

    private fun classpathFiles(classpath: String?): Set<File> =
        classpath?.split(File.pathSeparatorChar)
            ?.filter { it.isNotBlank() }
            ?.map { File(it) }
            ?.toSet()
            ?: emptySet()

    private fun usage(err: PrintStream, message: String): Int {
        err.println("error: $message")
        return EXIT_USAGE
    }

    private fun printHelp(stream: PrintStream) {
        stream.println(
            """
            agents-kt $version — Agents.KT permission manifest CLI (#1923)

            USAGE
              agents-kt <command> [options]

            COMMANDS
              generate   Generate the permission manifest from an entrypoint class
              inspect    Print a manifest file as json or yaml
              verify     Fail if a manifest widens a boundary vs an approved baseline
              help       Show this help
              version    Print the version

            generate --entrypoint <FQN> [--classpath <a${File.pathSeparatorChar}b>] [--format json|yaml] [--out <file>]
            inspect  <manifest.json> [--format json|yaml]
            verify   (--entrypoint <FQN> [--classpath <a${File.pathSeparatorChar}b>] | --current <file>) --baseline <file>

            EXIT CODES
              0 ok   1 verify findings (policy widened)   2 usage error   3 runtime error
            """.trimIndent(),
        )
    }

    private fun parse(args: List<String>): Parsed {
        val opts = LinkedHashMap<String, String>()
        val positionals = ArrayList<String>()
        var i = 0
        while (i < args.size) {
            val arg = args[i]
            when {
                arg.startsWith("--") && arg.contains('=') -> {
                    val (key, value) = arg.split("=", limit = 2)
                    opts[key] = value
                }
                arg.startsWith("--") -> {
                    val next = args.getOrNull(i + 1)
                    if (next != null && !next.startsWith("--")) {
                        opts[arg] = next
                        i++
                    } else {
                        opts[arg] = ""
                    }
                }
                else -> positionals.add(arg)
            }
            i++
        }
        return Parsed(opts, positionals)
    }

    private class Parsed(private val opts: Map<String, String>, val positionals: List<String>) {
        operator fun get(name: String): String? = opts[name]
    }
}
