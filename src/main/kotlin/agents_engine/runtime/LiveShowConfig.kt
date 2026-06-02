package agents_engine.runtime

import java.io.PrintWriter
import java.io.Reader

internal data class LiveShowConfig(
    val prompt: String,
    val maxHistoryTurns: Int,
    val historyDelimiter: String,
    val input: Reader,
    val inputIsDefault: Boolean,
    val output: PrintWriter,
    val colors: Boolean?,
    val useJLine: Boolean?,
    val theme: LiveShowTheme,
    val renderOutput: (Any?) -> String,
    val banner: (() -> String)?,
    val spinner: Spinner,
    val precheck: (() -> Unit)?,
    val userSlashes: Map<String, () -> Unit>,
    val onTurnStart: ((String) -> Unit)?,
    val onTurnEnd: ((String, Any?) -> Unit)?,
    val onErrorReported: ((Throwable) -> Unit)?,
)
