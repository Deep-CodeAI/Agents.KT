package agents_engine.runtime

import agents_engine.composition.branch.Branch
import agents_engine.composition.forum.Forum
import agents_engine.composition.loop.Loop
import agents_engine.composition.parallel.Parallel
import agents_engine.composition.pipeline.Pipeline
import agents_engine.core.Agent
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.io.Reader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking

// ─────────────────────────────────────────────────────────────────────────────
// UI surface (#983): ANSI color, themes, ASCII banner, spinner, hooks.
// ─────────────────────────────────────────────────────────────────────────────

/** ANSI escape codes for terminal coloring. [NONE] is a no-op pass-through. */
enum class AnsiColor(val code: String) {
    NONE(""),
    BLACK("[30m"),
    RED("[31m"),
    GREEN("[32m"),
    YELLOW("[33m"),
    BLUE("[34m"),
    MAGENTA("[35m"),
    CYAN("[36m"),
    WHITE("[37m"),
    BRIGHT_BLACK("[90m"),
    BRIGHT_RED("[91m"),
    BRIGHT_GREEN("[92m"),
    BRIGHT_YELLOW("[93m"),
    BRIGHT_BLUE("[94m"),
    BRIGHT_MAGENTA("[95m"),
    BRIGHT_CYAN("[96m"),
    BRIGHT_WHITE("[97m"),
    ;

    /** Wrap [s] in this color, resetting after. Returns [s] unchanged when [code] is empty. */
    fun wrap(s: String): String = if (code.isEmpty()) s else "$code$s[0m"

    companion object {
        private const val RESET = "[0m"
    }
}

/** Color theme for the LiveShow REPL. Roles map to ANSI colors; [NONE] disables all. */
data class LiveShowTheme(
    val prompt: AnsiColor,
    val agentOutput: AnsiColor,
    val error: AnsiColor,
    val slashOutput: AnsiColor,
    val banner: AnsiColor,
) {
    companion object {
        /** Pink/cyan/green default — magenta for the banner echoes the logo's accent. */
        val DEFAULT = LiveShowTheme(
            prompt = AnsiColor.BRIGHT_CYAN,
            agentOutput = AnsiColor.BRIGHT_GREEN,
            error = AnsiColor.RED,
            slashOutput = AnsiColor.YELLOW,
            banner = AnsiColor.BRIGHT_MAGENTA,
        )

        /** Plain text for every role — escape-code-free output. */
        val NONE = LiveShowTheme(
            prompt = AnsiColor.NONE,
            agentOutput = AnsiColor.NONE,
            error = AnsiColor.NONE,
            slashOutput = AnsiColor.NONE,
            banner = AnsiColor.NONE,
        )
    }
}

/**
 * In-place spinner shown while the agent is invoking. Each frame is rewritten
 * over the prior one with a carriage return; on completion the line is
 * cleared and the agent's output is printed in its place.
 */
data class Spinner(
    val frames: List<String>,
    val intervalMs: Long = 150L,
) {
    val isEmpty: Boolean get() = frames.isEmpty()

    companion object {
        /** ASCII cat with rotating face — fired during inference. */
        val CAT = Spinner(listOf(
            ">^_^<  thinking",
            ">^.^<  thinking.",
            ">-_-<  thinking..",
            ">^.^<  thinking...",
        ))

        /** No-op — disables the spinner. */
        val NONE = Spinner(emptyList())
    }
}

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
        val reader = BufferedReader(cfg.input)
        val writer = cfg.output
        val slashes = buildSlashTable()
        val history = ArrayDeque<Pair<String, String>>()

        cfg.banner?.invoke()?.let { writer.println(themed(it, cfg.theme.banner)) }
        writePrompt(writer)

        while (running.get()) {
            val raw = reader.readLine() ?: break
            val line = raw.trim()
            if (line.isEmpty()) {
                writePrompt(writer)
                continue
            }

            if (line.startsWith("/")) {
                handleSlash(writer, slashes, line, history)
                if (running.get()) writePrompt(writer)
                continue
            }

            handleTurn(writer, history, line)
            if (running.get()) writePrompt(writer)
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
            writer.print("\r[2K")
            writer.flush()
        }
    }

    private fun writePrompt(writer: PrintWriter) {
        if (cfg.prompt.isEmpty()) return
        writer.print(themed(cfg.prompt, cfg.theme.prompt))
        writer.flush()
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
        // Object-identity sentinel — distinguishable from any user value.
        private val SENTINEL_FAILURE: Any = Object()

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

class LiveShowBuilder {
    var prompt: String = "> "
    var maxHistoryTurns: Int = 20
    var historyDelimiter: String = "---"
    var input: Reader = InputStreamReader(System.`in`)
    var output: PrintWriter = PrintWriter(System.out, /* autoFlush = */ true)

    /** Force colors on/off; null = auto-detect via `System.console()`. */
    var colors: Boolean? = null

    /** Color scheme. [LiveShowTheme.NONE] disables theming regardless of [colors]. */
    var theme: LiveShowTheme = LiveShowTheme.DEFAULT

    /** Transform agent output before printing. Default `it?.toString() ?: "null"`. */
    var renderOutput: (Any?) -> String = { it?.toString() ?: "null" }

    /** Banner printed once at start. Default = the Agents.KT ASCII art. Set to null for none. */
    var banner: (() -> String)? = { DEFAULT_BANNER }

    /** In-place spinner shown during inference. [Spinner.NONE] disables. */
    var spinner: Spinner = Spinner.CAT

    internal val userSlashes: MutableMap<String, () -> Unit> = mutableMapOf()
    internal var onTurnStart: ((String) -> Unit)? = null
    internal var onTurnEnd: ((String, Any?) -> Unit)? = null
    internal var onErrorReported: ((Throwable) -> Unit)? = null

    fun slash(name: String, action: () -> Unit) {
        require(name.isNotBlank()) { "slash name must not be blank" }
        userSlashes[name] = action
    }

    /** Fires before each user-line invocation. Receives the user's input. */
    fun onTurnStart(block: (input: String) -> Unit) { onTurnStart = block }

    /** Fires after a successful invocation. Receives input and the agent's output. */
    fun onTurnEnd(block: (input: String, output: Any?) -> Unit) { onTurnEnd = block }

    /** Fires when an invocation throws. The user-visible "error: ..." line still prints. */
    fun onErrorReported(block: (Throwable) -> Unit) { onErrorReported = block }

    internal fun build() = LiveShowConfig(
        prompt = prompt,
        maxHistoryTurns = maxHistoryTurns,
        historyDelimiter = historyDelimiter,
        input = input,
        output = output,
        colors = colors,
        theme = theme,
        renderOutput = renderOutput,
        banner = banner,
        spinner = spinner,
        userSlashes = userSlashes.toMap(),
        onTurnStart = onTurnStart,
        onTurnEnd = onTurnEnd,
        onErrorReported = onErrorReported,
    )
}

internal data class LiveShowConfig(
    val prompt: String,
    val maxHistoryTurns: Int,
    val historyDelimiter: String,
    val input: Reader,
    val output: PrintWriter,
    val colors: Boolean?,
    val theme: LiveShowTheme,
    val renderOutput: (Any?) -> String,
    val banner: (() -> String)?,
    val spinner: Spinner,
    val userSlashes: Map<String, () -> Unit>,
    val onTurnStart: ((String) -> Unit)?,
    val onTurnEnd: ((String, Any?) -> Unit)?,
    val onErrorReported: ((Throwable) -> Unit)?,
)
