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

/**
 * REPL deployment surface for any String-input agent or composed structure (#981).
 *
 * Mirrors MCP's two-layer split — `LiveShow.from(agent).start()` is the
 * programmatic equivalent of `McpServer.from(agent).start()`. Multi-turn
 * chat-feel comes from a string-concatenated transcript that the runner
 * prepends to each new invocation; no Session model preempt.
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

    /** Begin reading from [LiveShowConfig.input] on the calling thread's executor. */
    fun start(): LiveShow {
        if (!running.compareAndSet(false, true)) return this
        // The REPL runs on a daemon thread so tests / programmatic callers can
        // stop() without their thread being blocked. main()-driven runs go
        // through runUntilTerminated() which blocks on the same latch.
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

    /** Stop the REPL loop (asynchronous; safe to call from any thread). */
    fun stop() {
        running.set(false)
    }

    /** Block the calling thread until the REPL loop exits. */
    fun runUntilTerminated() {
        terminated.await()
    }

    private fun runRepl() {
        val reader = BufferedReader(cfg.input)
        val writer = cfg.output

        // Pre-built slash table merging built-ins with user additions. User
        // entries take precedence so they can override `/help` etc.
        val slashes = buildSlashTable()

        val history = ArrayDeque<Pair<String, String>>()  // (user, assistant) pairs

        if (cfg.prompt.isNotEmpty()) writer.print(cfg.prompt).also { writer.flush() }

        while (running.get()) {
            val raw = reader.readLine() ?: break  // EOF
            val line = raw.trim()
            if (line.isEmpty()) {
                if (cfg.prompt.isNotEmpty()) writer.print(cfg.prompt).also { writer.flush() }
                continue
            }

            if (line.startsWith("/")) {
                val command = line.substring(1).substringBefore(' ')
                val handler = slashes[command]
                if (handler == null) {
                    writer.println("unknown command: /$command (try /help)")
                } else {
                    handler(SlashContext(writer, history, this))
                }
                if (running.get() && cfg.prompt.isNotEmpty())
                    writer.print(cfg.prompt).also { writer.flush() }
                continue
            }

            val composed = composeInput(history, line, cfg.historyDelimiter)
            val output = try {
                runBlocking { invoke(composed) }
            } catch (e: Throwable) {
                writer.println("error: ${e.message ?: e.toString()}")
                if (running.get() && cfg.prompt.isNotEmpty())
                    writer.print(cfg.prompt).also { writer.flush() }
                continue
            }

            val rendered = output?.toString() ?: "null"
            writer.println(rendered)

            if (cfg.maxHistoryTurns > 0) {
                history.addLast(line to rendered)
                while (history.size > cfg.maxHistoryTurns) history.removeFirst()
            }

            if (running.get() && cfg.prompt.isNotEmpty())
                writer.print(cfg.prompt).also { writer.flush() }
        }
    }

    private fun buildSlashTable(): Map<String, (SlashContext) -> Unit> {
        val builtins: Map<String, (SlashContext) -> Unit> = mapOf(
            "quit" to { ctx -> ctx.show.stop() },
            "exit" to { ctx -> ctx.show.stop() },
            "clear" to { ctx -> ctx.history.clear(); ctx.writer.println("(history cleared)") },
            "help" to { ctx ->
                ctx.writer.println("commands:")
                ctx.writer.println("  /quit, /exit  — leave the REPL")
                ctx.writer.println("  /clear        — wipe conversation history")
                ctx.writer.println("  /help         — print this help")
                cfg.userSlashes.keys.sorted().forEach { ctx.writer.println("  /$it") }
            },
        )
        // User overrides win.
        return builtins + cfg.userSlashes.mapValues { (_, action) ->
            { _: SlashContext -> action() }
        }
    }

    /** Internal context handed to slash handlers. */
    internal class SlashContext(
        val writer: PrintWriter,
        val history: ArrayDeque<Pair<String, String>>,
        val show: LiveShow,
    )

    companion object {
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

/**
 * Compose the runner's input for a turn. Empty history → raw input; otherwise
 * `--- user ---\n<u>\n--- assistant ---\n<a>\n... --- user ---\n<current>`.
 *
 * Internal so [LiveRunner] and tests can reuse the exact serialization shape.
 */
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

    internal val userSlashes: MutableMap<String, () -> Unit> = mutableMapOf()

    /** Register a slash command. Name is given without the leading `/`. */
    fun slash(name: String, action: () -> Unit) {
        require(name.isNotBlank()) { "slash name must not be blank" }
        userSlashes[name] = action
    }

    internal fun build() = LiveShowConfig(
        prompt = prompt,
        maxHistoryTurns = maxHistoryTurns,
        historyDelimiter = historyDelimiter,
        input = input,
        output = output,
        userSlashes = userSlashes.toMap(),
    )
}

internal data class LiveShowConfig(
    val prompt: String,
    val maxHistoryTurns: Int,
    val historyDelimiter: String,
    val input: Reader,
    val output: PrintWriter,
    val userSlashes: Map<String, () -> Unit>,
)
