package agents_engine.runtime

import org.jline.reader.EndOfFileException
import org.jline.reader.LineReader
import org.jline.reader.LineReaderBuilder
import org.jline.reader.UserInterruptException
import org.jline.reader.impl.history.DefaultHistory
import org.jline.terminal.Terminal
import org.jline.terminal.TerminalBuilder

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
