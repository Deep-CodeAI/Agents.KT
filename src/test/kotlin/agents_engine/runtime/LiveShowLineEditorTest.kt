package agents_engine.runtime

import java.io.ByteArrayOutputStream
import java.io.PrintWriter
import java.io.StringReader
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jline.reader.EndOfFileException
import org.jline.reader.LineReader
import org.jline.reader.UserInterruptException
import org.jline.reader.impl.history.DefaultHistory

class LiveShowLineEditorTest {

    @Test
    fun `BufferedLineEditor reads lines and returns null on EOF`() {
        val output = ByteArrayOutputStream()
        val editor = BufferedLineEditor(
            input = StringReader("hello\nworld\n"),
            output = PrintWriter(output, true),
        )

        assertEquals("hello", editor.readLine("p> "))
        assertEquals("world", editor.readLine("p> "))
        assertEquals(null, editor.readLine("p> "))
        assertEquals("p> p> p> ", output.toString())
    }

    @Test
    fun `JLineLineEditor delegates prompts to JLine reader`() {
        val scripted = ScriptedLineReader("hello", "world")
        val editor = JLineLineEditor(scripted.reader)

        assertEquals("hello", editor.readLine("j> "))
        assertEquals("world", editor.readLine("j> "))
        assertEquals(listOf("j> ", "j> "), scripted.prompts)
    }

    @Test
    fun `JLineLineEditor returns null on EOF`() {
        val editor = JLineLineEditor(ScriptedLineReader(EndOfFileException()).reader)

        assertEquals(null, editor.readLine("j> "))
    }

    @Test
    fun `JLineLineEditor returns null on user interrupt`() {
        val editor = JLineLineEditor(ScriptedLineReader(UserInterruptException("partial")).reader)

        assertEquals(null, editor.readLine("j> "))
    }

    @Test
    fun `JLine history traverses previous and next entries`() {
        val history = DefaultHistory()
        history.add("hello")
        history.add("world")

        history.moveToEnd()

        assertTrue(history.previous())
        assertEquals("world", history.current())
        assertTrue(history.previous())
        assertEquals("hello", history.current())
        assertTrue(history.next())
        assertEquals("world", history.current())
    }

    @Test
    fun `LiveShow uses JLine when forced on`() {
        val cfg = LiveShowBuilder().apply { useJLine = true }.build()

        assertEquals(LineEditorMode.JLINE, cfg.lineEditorMode(effectiveColors = false))
    }

    @Test
    fun `LiveShow uses BufferedLineEditor when forced off`() {
        val cfg = LiveShowBuilder().apply { useJLine = false }.build()

        assertEquals(LineEditorMode.BUFFERED, cfg.lineEditorMode(effectiveColors = true))
    }

    @Test
    fun `LiveShow auto-selects JLine for default Reader when colors are effective`() {
        val cfg = LiveShowBuilder().build()

        assertEquals(LineEditorMode.JLINE, cfg.lineEditorMode(effectiveColors = true))
    }

    @Test
    fun `LiveShow auto-selects BufferedLineEditor when colors are not effective`() {
        val cfg = LiveShowBuilder().build()

        assertEquals(LineEditorMode.BUFFERED, cfg.lineEditorMode(effectiveColors = false))
    }

    @Test
    fun `LiveShow uses BufferedLineEditor for custom Reader input`() {
        val cfg = LiveShowBuilder().apply {
            input = StringReader("/quit\n")
            colors = true
        }.build()

        assertEquals(LineEditorMode.BUFFERED, cfg.lineEditorMode(effectiveColors = true))
    }

    @Test
    fun `LiveShowBuilder preserves default input state when copied`() {
        val target = LiveShowBuilder().apply {
            copyInputStateFrom(LiveShowBuilder())
        }.build()

        assertEquals(LineEditorMode.JLINE, target.lineEditorMode(effectiveColors = true))
    }

    private class ScriptedLineReader(vararg entries: Any) {
        val prompts = mutableListOf<String>()
        private val responses = ArrayDeque<Any>().apply { entries.forEach(::addLast) }
        val reader: LineReader = Proxy.newProxyInstance(
            LineReader::class.java.classLoader,
            arrayOf(LineReader::class.java),
        ) { _, method, args ->
            when (method.name) {
                "readLine" -> {
                    prompts += args?.firstOrNull() as? String ?: ""
                    val response = responses.removeFirst()
                    if (response is RuntimeException) throw response
                    response
                }
                "toString" -> "ScriptedLineReader"
                "hashCode" -> System.identityHashCode(this)
                "equals" -> false
                else -> defaultReturn(method.returnType)
            }
        } as LineReader
    }

    private companion object {
        fun defaultReturn(type: Class<*>): Any? = when (type) {
            java.lang.Boolean.TYPE -> false
            java.lang.Byte.TYPE -> 0.toByte()
            java.lang.Short.TYPE -> 0.toShort()
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Float.TYPE -> 0f
            java.lang.Double.TYPE -> 0.0
            java.lang.Character.TYPE -> '\u0000'
            java.lang.Void.TYPE -> null
            else -> null
        }
    }
}
