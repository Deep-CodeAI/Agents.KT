package agents_engine.runtime

import agents_engine.core.agent
import java.io.ByteArrayOutputStream
import java.io.PrintWriter
import java.io.StringReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Tests for #983 — LiveShow UI polish:
//   - ANSI colors with TTY auto-detect, theme presets
//   - Lifecycle hooks (onTurnStart / onTurnEnd / onErrorReported)
//   - renderOutput override
//   - ASCII Agents.KT banner (default)
//   - ASCII cat spinner during inference
class LiveShowUiTest {

    private fun simpleAgent(transform: (String) -> String = { "OUT:$it" }) =
        agent<String, String>("a") {
            skills { skill<String, String>("op", "op") { implementedBy(transform) } }
        }

    // ─── Theme presets and codes ─────────────────────────────────────────

    @Test
    fun `LiveShowTheme NONE produces empty escape codes for every role`() {
        val t = LiveShowTheme.NONE
        assertEquals("", t.prompt.code)
        assertEquals("", t.agentOutput.code)
        assertEquals("", t.error.code)
        assertEquals("", t.slashOutput.code)
        assertEquals("", t.banner.code)
    }

    @Test
    fun `LiveShowTheme DEFAULT produces non-empty escape codes`() {
        val t = LiveShowTheme.DEFAULT
        assertTrue(t.prompt.code.isNotEmpty())
        assertTrue(t.agentOutput.code.isNotEmpty())
        assertTrue(t.error.code.isNotEmpty())
    }

    @Test
    fun `AnsiColor wrap returns plain text when code is empty`() {
        assertEquals("hello", AnsiColor.NONE.wrap("hello"))
    }

    @Test
    fun `AnsiColor wrap surrounds text with code and reset`() {
        val w = AnsiColor.RED.wrap("hello")
        assertTrue(w.startsWith(AnsiColor.RED.code))
        assertTrue(w.endsWith("[0m"))
        assertTrue(w.contains("hello"))
    }

    // ─── colors = false vs true vs auto ──────────────────────────────────

    @Test
    fun `colors = false produces no ANSI escapes regardless of theme`() {
        val out = ByteArrayOutputStream()
        LiveShow.from(simpleAgent()) {
            input = StringReader("hi\n/quit\n")
            output = PrintWriter(out, true)
            colors = false
            theme = LiveShowTheme.DEFAULT
            banner = null
        }.start().runUntilTerminated()

        assertTrue(!out.toString().contains("["), "no ANSI when colors=false: ${out.toString()}")
        assertTrue(out.toString().contains("OUT:hi"))
    }

    @Test
    fun `colors = true forces ANSI even when output is non-TTY`() {
        val out = ByteArrayOutputStream()
        LiveShow.from(simpleAgent()) {
            input = StringReader("hi\n/quit\n")
            output = PrintWriter(out, true)
            colors = true
            theme = LiveShowTheme.DEFAULT
            banner = null
            spinner = Spinner.NONE  // disable spinner so output is deterministic
        }.start().runUntilTerminated()

        val s = out.toString()
        assertTrue(s.contains("["), "ANSI expected when colors=true: $s")
        assertTrue(s.contains("[0m"), "reset code expected: $s")
    }

    @Test
    fun `colors = null with non-TTY output disables colors (auto-detect)`() {
        val out = ByteArrayOutputStream()
        LiveShow.from(simpleAgent()) {
            input = StringReader("hi\n/quit\n")
            output = PrintWriter(out, true)
            // colors left at null
            theme = LiveShowTheme.DEFAULT
            banner = null
        }.start().runUntilTerminated()

        assertTrue(!out.toString().contains("["), "auto-detect disables for non-TTY")
    }

    @Test
    fun `theme NONE produces no escapes even with colors = true`() {
        val out = ByteArrayOutputStream()
        LiveShow.from(simpleAgent()) {
            input = StringReader("hi\n/quit\n")
            output = PrintWriter(out, true)
            colors = true
            theme = LiveShowTheme.NONE
            banner = null
            spinner = Spinner.NONE
        }.start().runUntilTerminated()

        assertTrue(!out.toString().contains("["), "NONE theme + colors=true should still be plain")
    }

    // ─── renderOutput ────────────────────────────────────────────────────

    @Test
    fun `renderOutput transforms agent output before print`() {
        val out = ByteArrayOutputStream()
        LiveShow.from(simpleAgent()) {
            input = StringReader("hi\n/quit\n")
            output = PrintWriter(out, true)
            colors = false
            banner = null
            renderOutput = { v -> "<<${v}>>" }
        }.start().runUntilTerminated()

        assertTrue(out.toString().contains("<<OUT:hi>>"), "renderOutput should wrap output: ${out.toString()}")
    }

    @Test
    fun `renderOutput receives the agent's actual output value`() {
        val seen = mutableListOf<Any?>()
        LiveShow.from(simpleAgent { "OUT:$it" }) {
            input = StringReader("hi\n/quit\n")
            output = PrintWriter(ByteArrayOutputStream(), true)
            colors = false
            banner = null
            renderOutput = { v -> seen += v; v.toString() }
        }.start().runUntilTerminated()

        assertEquals(1, seen.size)
        assertEquals("OUT:hi", seen[0])
    }

    // ─── Lifecycle hooks ─────────────────────────────────────────────────

    @Test
    fun `onTurnStart fires with the user's input before invocation`() {
        val seen = mutableListOf<String>()
        var capturedDuringInvocation: String? = null

        val agent = agent<String, String>("a") {
            skills { skill<String, String>("op", "op") {
                implementedBy { _ ->
                    capturedDuringInvocation = seen.lastOrNull()
                    "answer"
                }
            }}
        }
        LiveShow.from(agent) {
            input = StringReader("hello\n/quit\n")
            output = PrintWriter(ByteArrayOutputStream(), true)
            colors = false
            banner = null
            maxHistoryTurns = 0
            onTurnStart { input -> seen += input }
        }.start().runUntilTerminated()

        assertEquals(listOf("hello"), seen)
        assertEquals("hello", capturedDuringInvocation, "onTurnStart must fire BEFORE invocation")
    }

    @Test
    fun `onTurnEnd fires after successful invocation`() {
        val ends = mutableListOf<Pair<String, Any?>>()
        LiveShow.from(simpleAgent { "OUT:$it" }) {
            input = StringReader("hi\n/quit\n")
            output = PrintWriter(ByteArrayOutputStream(), true)
            colors = false
            banner = null
            maxHistoryTurns = 0
            onTurnEnd { input, output -> ends += input to output }
        }.start().runUntilTerminated()

        assertEquals(1, ends.size)
        assertEquals("hi", ends[0].first)
        assertEquals("OUT:hi", ends[0].second)
    }

    @Test
    fun `onErrorReported fires on exception — onTurnEnd does NOT`() {
        val errors = mutableListOf<Throwable>()
        val ends = mutableListOf<Pair<String, Any?>>()
        val agent = agent<String, String>("a") {
            skills { skill<String, String>("op", "op") {
                implementedBy { error("boom") }
            }}
        }
        val out = ByteArrayOutputStream()
        LiveShow.from(agent) {
            input = StringReader("trigger\n/quit\n")
            output = PrintWriter(out, true)
            colors = false
            banner = null
            onErrorReported { errors += it }
            onTurnEnd { i, o -> ends += i to o }
        }.start().runUntilTerminated()

        assertEquals(1, errors.size)
        assertEquals("boom", errors.single().message)
        assertEquals(0, ends.size, "onTurnEnd must NOT fire on exception")
        assertTrue(out.toString().contains("error"), "user-visible error line still printed")
    }

    // ─── Banner ──────────────────────────────────────────────────────────

    @Test
    fun `default banner is the Agents-KT ASCII art`() {
        val out = ByteArrayOutputStream()
        LiveShow.from(simpleAgent()) {
            input = StringReader("/quit\n")
            output = PrintWriter(out, true)
            colors = false
            // banner left at default
        }.start().runUntilTerminated()

        val s = out.toString()
        // Strip whitespace and check for the framework name — the banner uses
        // letter-spaced "A G E N T S . K T" for visual weight.
        val condensed = s.replace(" ", "").replace("\n", "")
        assertTrue(
            condensed.contains("AGENTS", ignoreCase = true),
            "default banner should mention Agents.KT: $s",
        )
        // The geometric cat shape uses /\ ears and ◆ crown accents.
        assertTrue(
            s.contains("/\\") && s.contains("◆"),
            "default banner should include cat ears (/\\) and crown accents (◆): $s",
        )
    }

    @Test
    fun `banner = null produces no banner output`() {
        val out = ByteArrayOutputStream()
        LiveShow.from(simpleAgent()) {
            input = StringReader("/quit\n")
            output = PrintWriter(out, true)
            colors = false
            banner = null
        }.start().runUntilTerminated()

        // No cat; no Agents.KT mention beyond what may be in /help (not invoked).
        assertTrue(!out.toString().contains("^.^"), "no cat expected: ${out.toString()}")
    }

    @Test
    fun `custom banner is printed at start`() {
        val out = ByteArrayOutputStream()
        LiveShow.from(simpleAgent()) {
            input = StringReader("/quit\n")
            output = PrintWriter(out, true)
            colors = false
            banner = { "*** WELCOME TO THE SHOW ***" }
        }.start().runUntilTerminated()

        assertTrue(out.toString().contains("*** WELCOME TO THE SHOW ***"))
    }

    @Test
    fun `banner is themed with banner role when colors enabled`() {
        val out = ByteArrayOutputStream()
        LiveShow.from(simpleAgent()) {
            input = StringReader("/quit\n")
            output = PrintWriter(out, true)
            colors = true
            theme = LiveShowTheme.DEFAULT
            banner = { "WELCOME" }
            spinner = Spinner.NONE
        }.start().runUntilTerminated()

        val s = out.toString()
        assertTrue(s.contains("WELCOME"))
        if (LiveShowTheme.DEFAULT.banner.code.isNotEmpty()) {
            assertTrue(s.contains(LiveShowTheme.DEFAULT.banner.code), "banner color expected: $s")
        }
    }

    // ─── Themed prompt / output / error ──────────────────────────────────

    @Test
    fun `prompt is themed when colors enabled`() {
        val out = ByteArrayOutputStream()
        LiveShow.from(simpleAgent()) {
            input = StringReader("/quit\n")
            output = PrintWriter(out, true)
            prompt = "[demo] "
            colors = true
            theme = LiveShowTheme.DEFAULT
            banner = null
            spinner = Spinner.NONE
        }.start().runUntilTerminated()

        val s = out.toString()
        assertTrue(s.contains("[demo]"))
        assertTrue(s.contains(LiveShowTheme.DEFAULT.prompt.code))
    }

    @Test
    fun `agent output is themed with agentOutput role`() {
        val out = ByteArrayOutputStream()
        LiveShow.from(simpleAgent { "ANSWER" }) {
            input = StringReader("hi\n/quit\n")
            output = PrintWriter(out, true)
            colors = true
            theme = LiveShowTheme.DEFAULT
            banner = null
            spinner = Spinner.NONE
        }.start().runUntilTerminated()

        val s = out.toString()
        assertTrue(s.contains("ANSWER"))
        assertTrue(s.contains(LiveShowTheme.DEFAULT.agentOutput.code))
    }

    @Test
    fun `error line is themed with error role`() {
        val agent = agent<String, String>("a") {
            skills { skill<String, String>("op", "op") {
                implementedBy { error("nope") }
            }}
        }
        val out = ByteArrayOutputStream()
        LiveShow.from(agent) {
            input = StringReader("trigger\n/quit\n")
            output = PrintWriter(out, true)
            colors = true
            theme = LiveShowTheme.DEFAULT
            banner = null
            spinner = Spinner.NONE
        }.start().runUntilTerminated()

        val s = out.toString()
        assertTrue(s.contains("error"))
        assertTrue(s.contains(LiveShowTheme.DEFAULT.error.code))
    }

    // ─── Spinner ─────────────────────────────────────────────────────────

    @Test
    fun `Spinner CAT has multiple frames containing a cat face`() {
        assertTrue(Spinner.CAT.frames.size >= 2, "cat spinner should have multiple frames")
        assertTrue(
            Spinner.CAT.frames.any { it.contains("^") || it.contains(".") },
            "cat frames should resemble a cat face: ${Spinner.CAT.frames}",
        )
    }

    @Test
    fun `Spinner NONE has no frames and is treated as disabled`() {
        assertTrue(Spinner.NONE.isEmpty)
        assertEquals(0, Spinner.NONE.frames.size)
    }

    @Test
    fun `spinner does NOT print when colors are disabled`() {
        // colors=false also disables the spinner (would pollute pipe captures).
        val slow = agent<String, String>("slow") {
            skills { skill<String, String>("op", "op") {
                implementedBy { Thread.sleep(120); "DONE" }
            }}
        }
        val out = ByteArrayOutputStream()
        LiveShow.from(slow) {
            input = StringReader("hi\n/quit\n")
            output = PrintWriter(out, true)
            colors = false
            banner = null
            spinner = Spinner.CAT  // even with cat configured, no output
        }.start().runUntilTerminated()

        val s = out.toString()
        assertTrue(s.contains("DONE"), "agent output expected: $s")
        // No cat frames in the captured output.
        assertTrue(!s.contains("^_^"), "spinner must not print when colors disabled: $s")
    }

    @Test
    fun `spinner DOES print frames when colors are enabled and inference is slow`() {
        // Slow enough to render at least one frame at 50ms interval.
        val slow = agent<String, String>("slow") {
            skills { skill<String, String>("op", "op") {
                implementedBy { Thread.sleep(200); "DONE" }
            }}
        }
        val out = ByteArrayOutputStream()
        LiveShow.from(slow) {
            input = StringReader("hi\n/quit\n")
            output = PrintWriter(out, true)
            colors = true
            theme = LiveShowTheme.DEFAULT
            banner = null
            spinner = Spinner(Spinner.CAT.frames, intervalMs = 50L)
        }.start().runUntilTerminated()

        val s = out.toString()
        assertTrue(s.contains("DONE"))
        assertTrue(
            Spinner.CAT.frames.any { frame -> s.contains(frame) },
            "expected at least one cat frame in output: $s",
        )
        // After inference, the line should be cleared (carriage return present).
        assertTrue(s.contains("\r"), "spinner should leave a CR for line-clear: $s")
    }
}
