package agents_engine.runtime

import java.io.PrintWriter
import java.io.StringWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #2798 — pins the [SpinnerAnimation] AutoCloseable runner extracted out of `LiveShow.runWithSpinner`
 * (which spawned a manual Thread + an `AtomicBoolean running` that shadowed the class field). The
 * suppression contract (no colors / empty spinner → no animation) and the line-clear on close are the
 * CLI-visible behavior; both are now directly testable.
 */
class SpinnerAnimationTest {

    private val sink = StringWriter()
    private val writer = PrintWriter(sink)

    @Test
    fun `start returns null when disabled so the line stays clean for pipe captures`() {
        assertNull(SpinnerAnimation.start(writer, Spinner.CAT, enabled = false) { it })
    }

    @Test
    fun `start returns null when the spinner has no frames`() {
        assertNull(SpinnerAnimation.start(writer, Spinner.NONE, enabled = true) { it })
    }

    @Test
    fun `close erases the spinner line`() {
        val animation = SpinnerAnimation.start(writer, Spinner.CAT, enabled = true) { it }!!
        animation.close()
        assertTrue(sink.toString().contains(Ansi.ERASE_LINE), "close must clear the spinner frame")
    }

    @Test
    fun `use runs the block and returns its value even when suppressed`() {
        val result = SpinnerAnimation.start(writer, Spinner.NONE, enabled = false) { it }.use { "ok" }
        assertEquals("ok", result)
    }
}
