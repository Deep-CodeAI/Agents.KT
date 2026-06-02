package agents_engine.runtime

import agents_engine.composition.branch.Branch
import agents_engine.composition.forum.Forum
import agents_engine.composition.loop.Loop
import agents_engine.composition.parallel.Parallel
import agents_engine.composition.pipeline.Pipeline
import agents_engine.core.Agent
import java.io.PrintWriter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking

/**
 * `agents_engine/runtime/LiveShow.kt` — the demo REPL surface. Builds a
 * line-by-line interactive runner around an `Agent`, `Pipeline`,
 * `Branch`, `Loop`, `Parallel`, or `Forum`. Ships with ANSI color
 * themes, ASCII banner, spinner, command hooks, configurable history
 * trimming, optional precheck (e.g. [OllamaPreflight]). Used by every
 * runnable demo in the repo. See
 * `src/main/resources/internals-agent/runtime/LiveShow.md` (#1837 / #1890).
 */

/**
 * Default banner — full-resolution ASCII rendering of the Agents.KT logo.
 * Geometric cat face (`@`) with pink crown accents (`+`) and a block-letter
 * "Agents.KT" wordmark below. Approximately 140 columns wide; assumes a
 * standard wide terminal.
 */
internal val DEFAULT_BANNER: String =
    """
                                     @@                                                             @@
                                     @@@@@                                                       @@@@@
                                     @@@@@@@                                                  @@@@@@@@
                                      @@-@@@@@@                     +++                     @@@@@@:@@
                                      @@%..@@@@@@@               +++++++++               @@@@@@@=.#@@
                                      @@@...@@@@@@@@           +++++++++++++           @@@@@@@@...@@@
                                      @@@#...:@@@@@@@@@      +++++++++++++++++      @@@@@@@@@+...+@@@
                                      @@@@.....@@@@@@@@@@  +++++++++++++++++++++  @@@@@@@@@@.....@@@@
                                       @@@......@@@@@@@@@@@@@@@@@@@@%%@@@@@@@@@@@@@@@@@@@@-......@@@
                                       @@@@.......@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@.......@@@@
                                       @@@@........@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@+........@@@@
                                       @@@@+......@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@.......@@@@
                                       @@@@@....@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@....@@@@@
                                       @@@@@..@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@..@@@@@
                                       @@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@
                                       +@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@%+
                                     ++++@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@++++
                                   ++++++@@@@@@@@@%@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@++++++
                                 ++++++++@@@@@@@@@=.........#@@@@@@@@@@@@@@@@@..........@@@@@@@@@@++++++++
                               +++++++++++@@@@@@@@@@..........@@@@@@@@@@@@@@@.........-@@@@@@@@@@++++++++++
                             +++++++++++++@@@@@@@@@@@@.........+@@@@@@@@@@@..........@@@@@@@@@@@@+++++++++++++
                            ++++++++++++++@@@@@@@@@@@@@#........-@@@@#@@@@.........@@@@@@@@@@@@@@++++++++++++++
                              +++++++++++++@@@@@@@@@@@@@@@@@@@@@@@@@...@@@@@@@@@@@@@@@@@@@@@@@@@+++++++++++++
                                +++++++++++@@@@@@@@@@@@@@@@@@@@@@@@.....@@@@@@@@@@@@@@@@@@@@@@@@+++++++++++
                                  @++++++++*@@@@@@@@@@@@@@@@@@@@@@.......@@@@@@@@@@@@@@@@@@@@@@*++++++++%
                                    @%++++++@@@@@@@@@@@@@@@@@@@@@@-......@@@@@@@@@@@@@@@@@@@@@@+++++++@
                                      @@++++@@@@@@@@@@@@@@@@@@@@@@@@@.=@@@@@@@@@@@@@@@@@@@@@@@%++++#@
                                        @@+++@@@@@@@@@@@@@....@@@@@@@@@@@@@@=....#@@@@@@@@@@@@+++@@
                                          @@*@@@@@@@@....%@@@-@@@@@@@@@@@@@@..@@@#....@@@@@@@@+@@
                                            @@@@@@...@@@#...=@@@@@@@=.+@@@@@@@*...+@@@...@@@@@@
                                              @@@@@@@%...@@@@@@@@@.......@@@@@@@@@...+@@@@@@@
                                                @@@@@-@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@=%@@@@
                                                  @@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@
                                                    @@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@
                                                      @@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@
                                                        @@@@@@@@@@@@@@@@@@@@@@@@@@@
                                                          @@@@@@@@@@@@@@@@@@@@@@@
                                                            @@@@@@@@@@@@@@@@@@@
                                                              @@@@@@@@@@@@@@@
                                                                @@@@@@@@@@@
                                                                  @@@@@@@
                                                                    @@@


                                @@@                                     @@                @@@@   @@@ @@@@@@@@@@@
                               @@@@@                                   @@@                @@@@ @@@@  @@@@@@@@@@@
                              @@@@@@@   @@@@@@@@@  @@@@@@@@ @@@@@@@@@ @@@@@@@@@@@@@@@     @@@@@@@@       @@@
                             @@@@ @@@  @@@@   @@@ @@@@@@@@@ @@@@  @@@  @@@   @@@@@        @@@@@@@        @@@
                             @@@@@@@@@ @@@@   @@@ @@@@@@@@@ @@@   @@@@ @@@    @@@@@@@     @@@@@@@@       @@@
                            @@@@@@@@@@@ @@@@@@@@@ @@@@@@@@@ @@@   @@@@ @@@@@ @@@@@@@@ @@@ @@@@ @@@@@     @@@
                           @@@@     @@@@ @@@@@@@@   @@@@@@@ @@@   @@@   @@@@@@@@@@@@  @@@ @@@@   @@@@    @@@
                                        @@@@@@@@@
                                        @@@@@@@
""".trimIndent()

// ─────────────────────────────────────────────────────────────────────────────

/**
 * REPL deployment surface for any String-input agent or composed structure (#981).
 *
 * Mirrors MCP's two-layer split — `LiveShow.from(agent).start()` is the
 * programmatic equivalent of `McpServer.from(agent).start()`. Multi-turn
 * chat-feel comes from a string-concatenated transcript that the runner
 * prepends to each new invocation.
 *
 * UI polish (#983): ANSI color theme, ASCII banner, in-place spinner during
 * inference, lifecycle hooks (`onTurnStart` / `onTurnEnd` / `onErrorReported`),
 * `renderOutput` post-processor.
 *
 * Build via the [from] overloads, configure with [LiveShowBuilder], and call
 * [start] then [runUntilTerminated]. Stop early from another thread via
 * [stop].
 */
class LiveShow internal constructor(
    private val invoke: suspend (String) -> Any?,
    private val cfg: LiveShowConfig,
) {

    private val terminated = CountDownLatch(1)
    private val running = AtomicBoolean(false)

    /** True when the configured output is a real TTY OR colors were force-enabled. */
    private val effectiveColors: Boolean = when (cfg.colors) {
        true -> true
        false -> false
        null -> System.console() != null
    }

    fun start(): LiveShow {
        if (!running.compareAndSet(false, true)) return this
        Thread({
            try {
                runRepl()
            } finally {
                terminated.countDown()
                running.set(false)
            }
        }, "LiveShow-REPL").apply { isDaemon = true; start() }
        return this
    }

    fun stop() { running.set(false) }

    fun runUntilTerminated() { terminated.await() }

    private fun runRepl() {
        val writer = cfg.output
        val editor = cfg.createLineEditor(effectiveColors)
        val slashes = buildSlashTable()
        val history = ArrayDeque<Pair<String, String>>()

        try {
            cfg.banner?.invoke()?.let { writer.println(themed(it, cfg.theme.banner)) }

            while (running.get()) {
                val raw = editor.readLine(themed(cfg.prompt, cfg.theme.prompt)) ?: break
                val line = raw.trim()
                if (line.isEmpty()) continue

                if (line.startsWith("/")) {
                    handleSlash(writer, slashes, line, history)
                    continue
                }

                handleTurn(writer, history, line)
            }
        } finally {
            editor.close()
        }
    }

    private fun handleSlash(
        writer: PrintWriter,
        slashes: Map<String, (SlashContext) -> Unit>,
        line: String,
        history: ArrayDeque<Pair<String, String>>,
    ) {
        val command = line.substring(1).substringBefore(' ')
        val handler = slashes[command]
        if (handler == null) {
            writer.println(themed("unknown command: /$command (try /help)", cfg.theme.error))
        } else {
            handler(SlashContext(writer, history, this))
        }
    }

    private fun handleTurn(
        writer: PrintWriter,
        history: ArrayDeque<Pair<String, String>>,
        line: String,
    ) {
        cfg.onTurnStart?.invoke(line)

        val composed = composeInput(history, line, cfg.historyDelimiter)
        val output = runWithSpinner(writer) {
            try {
                runBlocking { invoke(composed) }
            } catch (e: Throwable) {
                cfg.onErrorReported?.invoke(e)
                writer.println(themed("error: ${e.message ?: e.toString()}", cfg.theme.error))
                return@runWithSpinner SENTINEL_FAILURE
            }
        }

        if (output === SENTINEL_FAILURE) return

        val rendered = cfg.renderOutput(output)
        writer.println(themed(rendered, cfg.theme.agentOutput))

        if (cfg.maxHistoryTurns > 0) {
            history.addLast(line to rendered)
            while (history.size > cfg.maxHistoryTurns) history.removeFirst()
        }

        cfg.onTurnEnd?.invoke(line, output)
    }

    /**
     * Run [block] while animating the configured [Spinner] in place. Spinner
     * is suppressed when colors are disabled (would pollute pipe captures).
     * Final line-clear uses CR + ANSI erase-line so the rendered output sits
     * cleanly where the spinner used to be.
     */
    private fun runWithSpinner(writer: PrintWriter, block: () -> Any?): Any? {
        if (!effectiveColors || cfg.spinner.isEmpty) return block()

        val running = AtomicBoolean(true)
        val thread = Thread({
            var idx = 0
            while (running.get()) {
                val frame = cfg.spinner.frames[idx % cfg.spinner.frames.size]
                writer.print("\r" + themed(frame, cfg.theme.prompt))
                writer.flush()
                try { Thread.sleep(cfg.spinner.intervalMs) } catch (_: InterruptedException) { break }
                idx++
            }
        }, "LiveShow-Spinner").apply { isDaemon = true; start() }

        try {
            return block()
        } finally {
            running.set(false)
            thread.interrupt()
            // Carriage return + ANSI erase-to-end-of-line clears the spinner.
            writer.print("\r${Ansi.ERASE_LINE}")
            writer.flush()
        }
    }

    private fun themed(s: String, color: AnsiColor): String =
        if (effectiveColors) color.wrap(s) else s

    private fun buildSlashTable(): Map<String, (SlashContext) -> Unit> {
        val builtins: Map<String, (SlashContext) -> Unit> = mapOf(
            "quit" to { ctx -> ctx.show.stop() },
            "exit" to { ctx -> ctx.show.stop() },
            "clear" to { ctx ->
                ctx.history.clear()
                ctx.writer.println(themed("(history cleared)", cfg.theme.slashOutput))
            },
            "help" to { ctx ->
                ctx.writer.println(themed("commands:", cfg.theme.slashOutput))
                ctx.writer.println(themed("  /quit, /exit  — leave the REPL", cfg.theme.slashOutput))
                ctx.writer.println(themed("  /clear        — wipe conversation history", cfg.theme.slashOutput))
                ctx.writer.println(themed("  /help         — print this help", cfg.theme.slashOutput))
                cfg.userSlashes.keys.sorted().forEach {
                    ctx.writer.println(themed("  /$it", cfg.theme.slashOutput))
                }
            },
        )
        return builtins + cfg.userSlashes.mapValues { (_, action) ->
            { _: SlashContext -> action() }
        }
    }

    internal class SlashContext(
        val writer: PrintWriter,
        val history: ArrayDeque<Pair<String, String>>,
        val show: LiveShow,
    )

    companion object {
        // Identity sentinel — distinguishable from any user value.
        private val SENTINEL_FAILURE: Any = Any()

        /**
         * #2801 — primary `from` entry point. Every operator type
         * (`Agent` / `Pipeline` / `Forum` / `Parallel` / `Loop` / `Branch`)
         * ultimately exposes a `suspend (String) -> Any?` callable; pass
         * it directly via a method reference to avoid the per-operator
         * overload fan-out:
         *
         * ```kotlin
         * LiveShow.from(myAgent::invokeSuspend) { theme = LiveShowTheme.NONE }
         * ```
         *
         * The six typed overloads below remain for source-compat and
         * IDE-completion ergonomics; they all delegate here. Future
         * operator types (Swarm, Stage, …) just call this overload
         * directly — no edit to `LiveShow` required.
         */
        fun from(invoke: suspend (String) -> Any?, block: LiveShowBuilder.() -> Unit = {}): LiveShow =
            buildShow(invoke, block)

        fun from(agent: Agent<String, *>, block: LiveShowBuilder.() -> Unit = {}): LiveShow =
            buildShow({ agent.invokeSuspend(it) }, block)

        fun from(pipeline: Pipeline<String, *>, block: LiveShowBuilder.() -> Unit = {}): LiveShow =
            buildShow({ pipeline.invokeSuspend(it) }, block)

        fun from(forum: Forum<String, *>, block: LiveShowBuilder.() -> Unit = {}): LiveShow =
            buildShow({ forum.invokeSuspend(it) }, block)

        fun from(parallel: Parallel<String, *>, block: LiveShowBuilder.() -> Unit = {}): LiveShow =
            buildShow({ parallel.invokeSuspend(it) }, block)

        fun from(loop: Loop<String, *>, block: LiveShowBuilder.() -> Unit = {}): LiveShow =
            buildShow({ loop.invokeSuspend(it) }, block)

        fun from(branch: Branch<String, *>, block: LiveShowBuilder.() -> Unit = {}): LiveShow =
            buildShow({ branch.invokeSuspend(it) }, block)

        private fun buildShow(
            invoke: suspend (String) -> Any?,
            block: LiveShowBuilder.() -> Unit,
        ): LiveShow {
            val builder = LiveShowBuilder().apply(block)
            return LiveShow(invoke, builder.build())
        }
    }
}

internal fun composeInput(
    history: List<Pair<String, String>>,
    current: String,
    delimiter: String,
): String {
    if (history.isEmpty()) return current
    val sb = StringBuilder()
    for ((user, assistant) in history) {
        sb.append(delimiter).append(" user ").append(delimiter).append('\n')
        sb.append(user).append('\n')
        sb.append(delimiter).append(" assistant ").append(delimiter).append('\n')
        sb.append(assistant).append('\n')
    }
    sb.append(delimiter).append(" user ").append(delimiter).append('\n')
    sb.append(current)
    return sb.toString()
}
