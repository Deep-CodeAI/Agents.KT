package agents_engine.runtime

import java.io.PrintWriter
import java.io.Reader
import org.jline.reader.EndOfFileException
import org.jline.reader.LineReader
import org.jline.reader.LineReaderBuilder
import org.jline.reader.UserInterruptException
import org.jline.reader.impl.history.DefaultHistory
import org.jline.terminal.Terminal
import org.jline.terminal.TerminalBuilder

/**
 * `agents_engine/runtime/LineEditor.kt` — LiveShow line-input abstraction.
 * Buffered mode preserves Reader/PrintWriter tests and scripted runs; JLine
 * mode gives interactive TTY runs cursor movement and in-memory history (#985).
 */
internal interface LineEditor : AutoCloseable {
    fun readLine(prompt: String): String?
    override fun close() {}
}

internal class BufferedLineEditor(
    private val input: Reader,
    private val output: PrintWriter,
) : LineEditor {
    private val reader = input.buffered()

    override fun readLine(prompt: String): String? {
        if (prompt.isNotEmpty()) {
            output.print(prompt)
            output.flush()
        }
        return reader.readLine()
    }
}

internal class JLineLineEditor private constructor(
    private val reader: LineReader,
    private val terminal: Terminal?,
) : LineEditor {
    constructor() : this(buildJLineState())

    internal constructor(reader: LineReader) : this(reader, null)

    private constructor(state: JLineState) : this(state.reader, state.terminal)

    override fun readLine(prompt: String): String? =
        try {
            reader.readLine(prompt)
        } catch (_: EndOfFileException) {
            null
        } catch (_: UserInterruptException) {
            null
        }

    override fun close() {
        terminal?.close()
    }

    private data class JLineState(
        val reader: LineReader,
        val terminal: Terminal,
    )

    companion object {
        private fun buildJLineState(): JLineState {
            val terminal = TerminalBuilder.builder()
                .name("agents-kt-liveshow")
                .system(true)
                .build()

            val reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .history(DefaultHistory())
                .build()

            return JLineState(reader, terminal)
        }
    }
}

internal enum class LineEditorMode {
    BUFFERED,
    JLINE,
}

internal fun LiveShowConfig.lineEditorMode(effectiveColors: Boolean): LineEditorMode {
    val effectiveJLine = useJLine ?: (effectiveColors && inputIsDefault)
    return if (effectiveJLine) LineEditorMode.JLINE else LineEditorMode.BUFFERED
}

internal fun LiveShowConfig.createLineEditor(effectiveColors: Boolean): LineEditor =
    when (lineEditorMode(effectiveColors)) {
        LineEditorMode.BUFFERED -> BufferedLineEditor(input, output)
        LineEditorMode.JLINE -> JLineLineEditor()
    }
