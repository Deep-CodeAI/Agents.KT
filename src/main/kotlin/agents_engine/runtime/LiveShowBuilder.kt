package agents_engine.runtime

import java.io.InputStreamReader
import java.io.PrintWriter
import java.io.Reader

class LiveShowBuilder {
    var prompt: String = "> "
    var maxHistoryTurns: Int = 20
    var historyDelimiter: String = "---"
    var input: Reader = InputStreamReader(System.`in`)
        set(value) {
            field = value
            inputOverridden = true
        }
    var output: PrintWriter = PrintWriter(System.out, /* autoFlush = */ true)

    private var inputOverridden: Boolean = false

    /** Force colors on/off; null = auto-detect via `System.console()`. */
    var colors: Boolean? = null

    /** Force JLine on/off; null = use JLine only for default interactive input. */
    var useJLine: Boolean? = null

    /** Color scheme. [LiveShowTheme.NONE] disables theming regardless of [colors]. */
    var theme: LiveShowTheme = LiveShowTheme.DEFAULT

    /** Transform agent output before printing. Default `it?.toString() ?: "null"`. */
    var renderOutput: (Any?) -> String = { it?.toString() ?: "null" }

    /** Banner printed once at start. Default = the Agents.KT ASCII art. Set to null for none. */
    var banner: (() -> String)? = { DEFAULT_BANNER }

    /** In-place spinner shown during inference. [Spinner.NONE] disables. */
    var spinner: Spinner = Spinner.CAT

    /**
     * Optional fail-fast endpoint check (#1132). Run by [LiveRunner] before
     * the banner / `--once` / REPL. Throw to abort startup — the runner prints
     * `error: <message>` and returns a non-zero exit code. Default is `null`
     * (no precheck). Typical use: `precheck = OllamaPreflight(host, port)::check`.
     */
    var precheck: (() -> Unit)? = null

    internal val userSlashes: MutableMap<String, () -> Unit> = mutableMapOf()
    internal var onTurnStart: ((String) -> Unit)? = null
    internal var onTurnEnd: ((String, Any?) -> Unit)? = null
    internal var onErrorReported: ((Throwable) -> Unit)? = null

    internal fun copyInputStateFrom(other: LiveShowBuilder) {
        input = other.input
        inputOverridden = other.inputOverridden
    }

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
        inputIsDefault = !inputOverridden,
        output = output,
        colors = colors,
        useJLine = useJLine,
        theme = theme,
        renderOutput = renderOutput,
        banner = banner,
        spinner = spinner,
        precheck = precheck,
        userSlashes = userSlashes.toMap(),
        onTurnStart = onTurnStart,
        onTurnEnd = onTurnEnd,
        onErrorReported = onErrorReported,
    )
}
