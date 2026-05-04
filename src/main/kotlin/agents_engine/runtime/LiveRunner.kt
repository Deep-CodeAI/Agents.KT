package agents_engine.runtime

import agents_engine.composition.branch.Branch
import agents_engine.composition.forum.Forum
import agents_engine.composition.loop.Loop
import agents_engine.composition.parallel.Parallel
import agents_engine.composition.pipeline.Pipeline
import agents_engine.core.Agent
import java.io.PrintWriter
import kotlinx.coroutines.runBlocking

/**
 * Picocli-shaped one-line `main` for the [LiveShow] REPL (#981). Mirrors
 * [agents_engine.mcp.McpRunner.serve] in shape and lifecycle.
 *
 * ```kotlin
 * fun main(args: Array<String>) = exitProcess(
 *     LiveRunner.serve(coder, args) { prompt = "coder> " }
 * )
 * ```
 *
 * Flags:
 * - `--once "<prompt>"` — non-interactive: one invocation, write output, exit
 * - `--max-history N`   — override the builder's `maxHistoryTurns`
 * - `-h, --help`        — print usage, exit 0
 * - `-V, --version`     — print version, exit 0
 *
 * Returns the process exit code. Block-defaults are overridden by CLI flags
 * when both are given.
 */
object LiveRunner {

    private const val VERSION = "0.2.0"

    fun serve(
        agent: Agent<String, *>,
        args: Array<String>,
        configure: LiveShowBuilder.() -> Unit = {},
    ): Int = run(args, configure, { agent.invokeSuspend(it) }) { LiveShow.from(agent, it) }

    fun serve(
        pipeline: Pipeline<String, *>,
        args: Array<String>,
        configure: LiveShowBuilder.() -> Unit = {},
    ): Int = run(args, configure, { pipeline.invokeSuspend(it) }) { LiveShow.from(pipeline, it) }

    fun serve(
        forum: Forum<String, *>,
        args: Array<String>,
        configure: LiveShowBuilder.() -> Unit = {},
    ): Int = run(args, configure, { forum.invokeSuspend(it) }) { LiveShow.from(forum, it) }

    fun serve(
        parallel: Parallel<String, *>,
        args: Array<String>,
        configure: LiveShowBuilder.() -> Unit = {},
    ): Int = run(args, configure, { parallel.invokeSuspend(it) }) { LiveShow.from(parallel, it) }

    fun serve(
        loop: Loop<String, *>,
        args: Array<String>,
        configure: LiveShowBuilder.() -> Unit = {},
    ): Int = run(args, configure, { loop.invokeSuspend(it) }) { LiveShow.from(loop, it) }

    fun serve(
        branch: Branch<String, *>,
        args: Array<String>,
        configure: LiveShowBuilder.() -> Unit = {},
    ): Int = run(args, configure, { branch.invokeSuspend(it) }) { LiveShow.from(branch, it) }

    /**
     * Single shared dispatcher: parse CLI, possibly print help/version, dispatch
     * --once or interactive REPL. The function args are bound per-overload so
     * the same parsed args build the right LiveShow and the right one-shot
     * invocation.
     */
    private fun run(
        args: Array<String>,
        configure: LiveShowBuilder.() -> Unit,
        once: suspend (String) -> Any?,
        makeShow: (LiveShowBuilder.() -> Unit) -> LiveShow,
    ): Int {
        val parsed = parseArgs(args, configure)
        val out = parsed.builder.output

        if (parsed.helpRequested) { printHelp(out); return 0 }
        if (parsed.versionRequested) { out.println("Agents.KT $VERSION"); return 0 }
        if (parsed.errors.isNotEmpty()) {
            parsed.errors.forEach { out.println("error: $it") }
            printHelp(out)
            return 2
        }

        parsed.once?.let { prompt ->
            return try {
                val result = runBlocking { once(prompt) }
                out.println(result?.toString() ?: "null")
                0
            } catch (e: Throwable) {
                out.println("error: ${e.message ?: e}")
                2
            }
        }

        val show = makeShow {
            // Apply user's block first, then overlay CLI flag overrides.
            configure()
            // CLI flag wins: the parsed builder's maxHistoryTurns reflects --max-history.
            this.maxHistoryTurns = parsed.builder.maxHistoryTurns
            this.input = parsed.builder.input
            this.output = parsed.builder.output
            this.prompt = parsed.builder.prompt
            this.historyDelimiter = parsed.builder.historyDelimiter
        }
        val shutdown = Thread { runCatching { show.stop() } }
        Runtime.getRuntime().addShutdownHook(shutdown)
        try {
            show.start().runUntilTerminated()
        } finally {
            runCatching { Runtime.getRuntime().removeShutdownHook(shutdown) }
        }
        return 0
    }

    internal fun parseArgs(
        args: Array<String>,
        configure: LiveShowBuilder.() -> Unit,
    ): ParsedArgs {
        val builder = LiveShowBuilder().apply(configure)
        val errors = mutableListOf<String>()
        var help = false
        var version = false
        var once: String? = null

        var i = 0
        while (i < args.size) {
            when (val a = args[i]) {
                "-h", "--help" -> help = true
                "-V", "--version" -> version = true
                "--once" -> {
                    val v = args.getOrNull(++i)
                    if (v == null) errors += "--once requires a value"
                    else once = v
                }
                "--max-history" -> {
                    val raw = args.getOrNull(++i)
                    if (raw == null) errors += "--max-history requires a value"
                    else {
                        val parsed = raw.toIntOrNull()
                        if (parsed == null || parsed < 0) errors += "invalid --max-history value: \"$raw\""
                        else builder.maxHistoryTurns = parsed
                    }
                }
                else -> errors += "unknown flag: $a"
            }
            i++
        }

        return ParsedArgs(
            builder = builder,
            once = once,
            helpRequested = help,
            versionRequested = version,
            errors = errors,
        )
    }

    private fun printHelp(out: PrintWriter) {
        out.println("""
            Agents.KT $VERSION — LiveShow REPL runner

            Usage:
              <main> [options]

            Options:
              --once "<prompt>"   Run a single non-interactive turn and exit
              --max-history N     Cap conversation history at N user/assistant pairs (default: 20)
              -h, --help          Print this help and exit
              -V, --version       Print version and exit
        """.trimIndent())
    }

    internal data class ParsedArgs(
        val builder: LiveShowBuilder,
        val once: String?,
        val helpRequested: Boolean,
        val versionRequested: Boolean,
        val errors: List<String>,
    )
}
