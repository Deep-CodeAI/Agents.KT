package agents_engine.runtime

/**
 * `agents_engine/runtime/LineEditor.kt` — LiveShow line-input abstraction.
 * Buffered mode preserves Reader/PrintWriter tests and scripted runs; JLine
 * mode gives interactive TTY runs cursor movement and in-memory history (#985).
 */
internal interface LineEditor : AutoCloseable {
    fun readLine(prompt: String): String?
    override fun close() {}
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
