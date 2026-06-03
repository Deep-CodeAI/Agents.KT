package agents_engine.runtime

import java.io.PrintWriter
import java.util.concurrent.atomic.AtomicBoolean

/**
 * `agents_engine/runtime/SpinnerAnimation.kt` — the in-place inference spinner, extracted out of
 * `LiveShow.runWithSpinner` (#2798). That method tangled threading, lifecycle, and rendering, and its
 * local `AtomicBoolean running` shadowed the `LiveShow.running` field. Here the animation owns one
 * daemon thread and an [active] flag (no shadow), and implements [AutoCloseable] so the turn handler
 * reads as `spinner.use { … }` — `close()` stops the thread and clears the spinner line.
 *
 * Frames are rewritten in place with a carriage return; [render] themes each frame (the caller passes
 * its theming function). The final line-clear uses CR + [Ansi.ERASE_LINE] so the agent's output sits
 * cleanly where the spinner was.
 */
internal class SpinnerAnimation private constructor(
    private val writer: PrintWriter,
    private val spinner: Spinner,
    private val render: (String) -> String,
) : AutoCloseable {

    private val active = AtomicBoolean(true)

    private val thread = Thread({
        var idx = 0
        while (active.get()) {
            val frame = spinner.frames[idx % spinner.frames.size]
            writer.print("\r" + render(frame))
            writer.flush()
            try {
                Thread.sleep(spinner.intervalMs)
            } catch (_: InterruptedException) {
                break
            }
            idx++
        }
    }, "LiveShow-Spinner").apply { isDaemon = true }

    override fun close() {
        active.set(false)
        thread.interrupt()
        // Carriage return + ANSI erase-to-end-of-line clears the spinner frame.
        writer.print("\r${Ansi.ERASE_LINE}")
        writer.flush()
    }

    companion object {
        /**
         * Starts the spinner, or returns `null` when spinning is suppressed — colors disabled (would
         * pollute pipe captures) or the spinner has no frames. A `null` is safe to drive through the
         * nullable-`AutoCloseable` `use { }` overload: the block still runs and `close()` is a no-op.
         */
        fun start(
            writer: PrintWriter,
            spinner: Spinner,
            enabled: Boolean,
            render: (String) -> String,
        ): SpinnerAnimation? {
            if (!enabled || spinner.isEmpty) return null
            return SpinnerAnimation(writer, spinner, render).also { it.thread.start() }
        }
    }
}
