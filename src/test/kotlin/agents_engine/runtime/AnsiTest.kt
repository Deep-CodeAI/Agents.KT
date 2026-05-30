package agents_engine.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #2806 — pins the central [Ansi] constants so the consolidation is
 * regression-proof. Before the cleanup, raw escape strings (`[0m`,
 * `[2K`) and a dead `RESET` companion inside `AnsiColor` lived side by
 * side. These tests assert one path: every color goes through [Ansi.ESC];
 * `wrap` ends with [Ansi.RESET]; the spinner clear uses [Ansi.ERASE_LINE].
 */
class AnsiTest {

    private val esc = ""

    @Test
    fun `Ansi ESC is the literal escape byte`() {
        assertEquals(esc, Ansi.ESC)
    }

    @Test
    fun `Ansi RESET is ESC plus the SGR-0 sequence`() {
        assertEquals("$esc[0m", Ansi.RESET)
    }

    @Test
    fun `Ansi ERASE_LINE is ESC plus the CSI-2K sequence`() {
        assertEquals("$esc[2K", Ansi.ERASE_LINE)
    }

    @Test
    fun `AnsiColor codes share the Ansi ESC prefix (excluding NONE)`() {
        for (color in AnsiColor.entries) {
            if (color == AnsiColor.NONE) continue
            assertTrue(
                color.code.startsWith(Ansi.ESC),
                "AnsiColor.${color.name}.code does not start with Ansi.ESC: ${color.code.toCharArray().toList()}"
            )
        }
    }

    @Test
    fun `AnsiColor wrap terminates with Ansi RESET`() {
        val wrapped = AnsiColor.RED.wrap("hello")
        assertTrue(wrapped.endsWith(Ansi.RESET), "wrap should terminate with Ansi.RESET: $wrapped")
    }

    @Test
    fun `AnsiColor wrap of NONE passes through unchanged`() {
        assertEquals("hello", AnsiColor.NONE.wrap("hello"))
    }
}
