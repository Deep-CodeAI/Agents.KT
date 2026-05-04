package agents_engine.mcp

import agents_engine.core.Agent
import java.util.concurrent.CountDownLatch

/**
 * One-line `main` for exposing an agent over MCP. Picocli-shaped:
 *
 * ```kotlin
 * fun main(args: Array<String>) = exitProcess(McpRunner.serve(coder, args) {
 *     port = 8080
 *     expose("write-code", "review-code")
 * })
 * ```
 *
 * The block sets defaults; CLI flags override them.
 *
 * Flags:
 * - `--port N` — bind port (default 0 = OS-assigned)
 * - `--expose NAME` — skill name to expose (repeatable; replaces block exposes if any --expose is passed)
 * - `-h, --help` — print usage and return 0
 * - `-V, --version` — print Agents.KT version and return 0
 *
 * Returns the process exit code.
 */
object McpRunner {

    private const val VERSION = "0.2.3"

    fun serve(
        agent: Agent<*, *>,
        args: Array<String>,
        configure: McpRunnerBuilder.() -> Unit = {},
    ): Int {
        val cfg = resolveConfig(args, configure)

        if (cfg.helpRequested) { printHelp(); return 0 }
        if (cfg.versionRequested) { println("Agents.KT $VERSION"); return 0 }
        if (cfg.errors.isNotEmpty()) {
            cfg.errors.forEach { System.err.println("error: $it") }
            System.err.println()
            printHelp(System.err)
            return 2
        }

        val server = try {
            McpServer.from(agent) {
                port = cfg.port
                cfg.exposeNames.forEach { expose(it) }
            }.start()
        } catch (e: Exception) {
            System.err.println("error: ${e.message ?: e}")
            return 2
        }

        println("Listening on ${server.url}")

        val shutdown = Thread { runCatching { server.stop() } }
        Runtime.getRuntime().addShutdownHook(shutdown)

        cfg.onStarted(server)

        // Block until something stops us. We poll a latch released by the shutdown hook
        // OR break early if the server gets stopped externally (used by tests).
        val terminated = CountDownLatch(1)
        val stopWatcher = Thread({
            while (server.isRunning()) Thread.sleep(50)
            terminated.countDown()
        }, "McpRunner-stop-watcher").apply { isDaemon = true; start() }

        try {
            terminated.await()
        } catch (_: InterruptedException) {
            // Thread interrupted (test scenario)
        } finally {
            runCatching { Runtime.getRuntime().removeShutdownHook(shutdown) }
            runCatching { server.stop() }
            stopWatcher.interrupt()
        }
        return 0
    }

    internal fun resolveConfig(
        args: Array<String>,
        configure: McpRunnerBuilder.() -> Unit = {},
    ): RunnerConfig {
        val builder = McpRunnerBuilder().apply(configure)
        val errors = mutableListOf<String>()
        var port = builder.port
        val cliExposes = mutableListOf<String>()
        var help = false
        var version = false

        var i = 0
        while (i < args.size) {
            when (val a = args[i]) {
                "-h", "--help" -> help = true
                "-V", "--version" -> version = true
                "--port" -> {
                    val raw = args.getOrNull(++i)
                    if (raw == null) {
                        errors += "--port requires a value"
                    } else {
                        val parsed = raw.toIntOrNull()
                        if (parsed == null) errors += "invalid port value: \"$raw\""
                        else if (parsed !in 0..65535) errors += "port out of range: $parsed (allowed 0..65535)"
                        else port = parsed
                    }
                }
                "--expose" -> {
                    val name = args.getOrNull(++i)
                    if (name == null) errors += "--expose requires a skill name"
                    else cliExposes += name
                }
                else -> errors += "unknown flag: $a"
            }
            i++
        }

        val finalExposes = if (cliExposes.isNotEmpty()) cliExposes else builder.blockExposes.toList()

        return RunnerConfig(
            port = port,
            exposeNames = finalExposes,
            helpRequested = help,
            versionRequested = version,
            errors = errors,
            onStarted = builder.onStartedHandler,
        )
    }

    private fun printHelp(out: java.io.PrintStream = System.out) {
        out.println("""
            Agents.KT $VERSION — MCP server runner

            Usage:
              <main> [options]

            Options:
              --port N         Bind port (default: 0 = OS-assigned)
              --expose NAME    Skill to expose (repeatable; replaces block defaults)
              -h, --help       Print this help and exit
              -V, --version    Print version and exit
        """.trimIndent())
    }
}

class McpRunnerBuilder internal constructor() {
    var port: Int = 0
    internal val blockExposes = mutableListOf<String>()
    internal var onStartedHandler: (McpServer) -> Unit = {}

    fun expose(vararg names: String) { blockExposes.addAll(names) }

    /** Test hook: invoked after the server starts, with the running [McpServer]. */
    var onStarted: (McpServer) -> Unit
        get() = onStartedHandler
        set(value) { onStartedHandler = value }
}

internal data class RunnerConfig(
    val port: Int,
    val exposeNames: List<String>,
    val helpRequested: Boolean,
    val versionRequested: Boolean,
    val errors: List<String>,
    val onStarted: (McpServer) -> Unit,
)
