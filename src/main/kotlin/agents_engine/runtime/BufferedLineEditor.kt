package agents_engine.runtime

import java.io.PrintWriter
import java.io.Reader

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
