package agents_engine.mcp

import agents_engine.core.Agent
import java.util.concurrent.CountDownLatch

/**
 * `agents_engine/mcp/McpRunner.kt` — the one-line `main` for exposing
 * an agent over MCP. Returns a process exit code. Honors CLI flags
 * `--port N`, `--stdio`, `--expose NAME` (repeatable), `-h / --help`, `-V /
 * --version`. The configuration block sets defaults; CLI flags
 * override. See `src/main/resources/internals-agent/mcp/McpRunner.md`
 * (#1837 / #1883).
 */

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
 * - `--stdio` — serve line-delimited MCP over stdin/stdout instead of HTTP
 * - `--expose NAME` — skill name to expose (repeatable; replaces block exposes if any --expose is passed)
 * - `-h, --help` — print usage and return 0
 * - `-V, --version` — print Agents.KT version and return 0
 *
 * Returns the process exit code.
 */
object McpRunner {

    // #2806 — was a hardcoded "0.3.0"; now sourced from BuildInfo so
    // `--version` prints what's actually built.
    private val VERSION: String = agents_engine.internal.BuildInfo.version

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

        if (cfg.stdioRequested) {
            val server = try {
                McpStdioServer.from(agent) {
                    cfg.exposeNames.forEach { expose(it) }
                }
            } catch (e: Exception) {
                System.err.println("error: ${e.message ?: e}")
                return 2
            }
            cfg.onStdioStarted(server)
            server.serve()
            return 0
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
        var stdio = builder.stdio

        var i = 0
        while (i < args.size) {
            when (val a = args[i]) {
                "-h", "--help" -> help = true
                "-V", "--version" -> version = true
                "--stdio" -> stdio = true
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
            stdioRequested = stdio,
            errors = errors,
            onStarted = builder.onStartedHandler,
            onStdioStarted = builder.onStdioStartedHandler,
        )
    }

    private fun printHelp(out: java.io.PrintStream = System.out) {
        out.println("""
            Agents.KT $VERSION — MCP server runner

            Usage:
              <main> [options]

            Options:
              --port N         Bind port (default: 0 = OS-assigned)
              --stdio          Serve over stdin/stdout instead of HTTP
              --expose NAME    Skill to expose (repeatable; replaces block defaults)
              -h, --help       Print this help and exit
              -V, --version    Print version and exit
        """.trimIndent())
    }
}
