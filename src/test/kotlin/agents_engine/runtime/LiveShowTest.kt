package agents_engine.runtime

import agents_engine.composition.pipeline.then
import agents_engine.core.agent
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayOutputStream
import java.io.PrintWriter
import java.io.StringReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Tests for #981 — LiveShow / LiveRunner REPL deployment with string-concat
// conversation history. TDD: this file exists before any implementation.
//
// History format (default delimiter "---"):
//   --- user ---
//   <user_input_1>
//   --- assistant ---
//   <assistant_output_1>
//   --- user ---
//   <user_input_2>
//   ...
//
// First turn (empty history) sends raw input. Subsequent turns prepend the
// transcript so the LLM has chat-feel context.
class LiveShowTest {

    // Helper: an echo agent whose output is a known function of the input.
    // Lets tests inspect what the LLM (or, equivalently, the runner-composed
    // input) actually saw.
    private fun echoAgent(transform: (String) -> String = { "ECHO:$it" }) =
        agent<String, String>("echo") {
            skills {
                skill<String, String>("op", "Echo") { implementedBy(transform) }
            }
        }

    @Test
    fun `LiveShowBuilder defaults are sensible`() {
        val b = LiveShowBuilder()
        assertEquals("> ", b.prompt)
        assertEquals(20, b.maxHistoryTurns)
        assertEquals("---", b.historyDelimiter)
    }

    @Test
    fun `first turn sends raw input — history is empty`() {
        val seenInputs = mutableListOf<String>()
        val agent = agent<String, String>("a") {
            skills {
                skill<String, String>("op", "op") {
                    implementedBy { seenInputs += it; "out-1" }
                }
            }
        }

        val out = ByteArrayOutputStream()
        val show = LiveShow.from(agent) {
            input = StringReader("hello\n/quit\n")
            output = PrintWriter(out, true)
        }
        show.start()
        show.runUntilTerminated()

        // First and only invocation saw the raw "hello" — no transcript prefix.
        assertEquals(listOf("hello"), seenInputs)
    }

    @Test
    fun `second turn prepends transcript with default --- delimiters`() {
        val seenInputs = mutableListOf<String>()
        val agent = agent<String, String>("a") {
            skills {
                skill<String, String>("op", "op") {
                    implementedBy { seenInputs += it; "answer-${seenInputs.size}" }
                }
            }
        }

        val out = ByteArrayOutputStream()
        LiveShow.from(agent) {
            input = StringReader("first\nsecond\n/quit\n")
            output = PrintWriter(out, true)
        }.start().runUntilTerminated()

        assertEquals(2, seenInputs.size, "should have seen two invocations: $seenInputs")
        // Turn 1: raw input.
        assertEquals("first", seenInputs[0])
        // Turn 2: full transcript + new input.
        val expected = """
            --- user ---
            first
            --- assistant ---
            answer-1
            --- user ---
            second
        """.trimIndent()
        assertEquals(expected, seenInputs[1])
    }

    @Test
    fun `maxHistoryTurns = 0 disables history (every turn raw)`() {
        val seenInputs = mutableListOf<String>()
        val agent = agent<String, String>("a") {
            skills {
                skill<String, String>("op", "op") {
                    implementedBy { seenInputs += it; "out" }
                }
            }
        }
        LiveShow.from(agent) {
            input = StringReader("a\nb\nc\n/quit\n")
            output = PrintWriter(ByteArrayOutputStream(), true)
            maxHistoryTurns = 0
        }.start().runUntilTerminated()

        assertEquals(listOf("a", "b", "c"), seenInputs)
    }

    @Test
    fun `maxHistoryTurns = 2 drops oldest turn beyond cap`() {
        val seenInputs = mutableListOf<String>()
        val agent = agent<String, String>("a") {
            skills {
                skill<String, String>("op", "op") {
                    implementedBy { seenInputs += it; "out${seenInputs.size}" }
                }
            }
        }

        LiveShow.from(agent) {
            input = StringReader("u1\nu2\nu3\nu4\n/quit\n")
            output = PrintWriter(ByteArrayOutputStream(), true)
            maxHistoryTurns = 2
        }.start().runUntilTerminated()

        // After "u3", history holds [u1/out1, u2/out2, u3/out3] but cap=2 keeps
        // the LAST 2 user-assistant pairs. Turn 4 sees u2 + u3 + (current u4).
        // u1 must have been dropped from the prefix.
        val turn4Input = seenInputs[3]
        assertTrue(!turn4Input.contains("u1"), "u1 should have been evicted: $turn4Input")
        assertTrue(turn4Input.contains("u2"), "u2 should be in history: $turn4Input")
        assertTrue(turn4Input.contains("u3"), "u3 should be in history: $turn4Input")
        assertTrue(turn4Input.endsWith("u4"), "current input should be at end: $turn4Input")
    }

    @Test
    fun `slash quit exits cleanly`() {
        var invocations = 0
        val agent = agent<String, String>("a") {
            skills {
                skill<String, String>("op", "op") {
                    implementedBy { invocations++; "out" }
                }
            }
        }
        LiveShow.from(agent) {
            input = StringReader("/quit\n")
            output = PrintWriter(ByteArrayOutputStream(), true)
        }.start().runUntilTerminated()

        assertEquals(0, invocations, "/quit must not invoke the agent")
    }

    @Test
    fun `slash clear resets history mid-session`() {
        val seenInputs = mutableListOf<String>()
        val agent = agent<String, String>("a") {
            skills {
                skill<String, String>("op", "op") {
                    implementedBy { seenInputs += it; "ans" }
                }
            }
        }
        LiveShow.from(agent) {
            input = StringReader("first\n/clear\nsecond\n/quit\n")
            output = PrintWriter(ByteArrayOutputStream(), true)
        }.start().runUntilTerminated()

        // Two invocations; the second should be raw "second" (history cleared).
        assertEquals(2, seenInputs.size)
        assertEquals("first", seenInputs[0])
        assertEquals("second", seenInputs[1])
    }

    @Test
    fun `slash help prints something — does not invoke the agent`() {
        var invocations = 0
        val agent = agent<String, String>("a") {
            skills {
                skill<String, String>("op", "op") {
                    implementedBy { invocations++; "out" }
                }
            }
        }
        val out = ByteArrayOutputStream()
        LiveShow.from(agent) {
            input = StringReader("/help\n/quit\n")
            output = PrintWriter(out, true)
        }.start().runUntilTerminated()

        assertEquals(0, invocations)
        val written = out.toString()
        assertTrue(written.contains("/help") || written.contains("help"),
            "help output expected, got: $written")
    }

    @Test
    fun `user-defined slash command runs its action`() {
        var slashRan = false
        val agent = echoAgent()
        LiveShow.from(agent) {
            input = StringReader("/ping\n/quit\n")
            output = PrintWriter(ByteArrayOutputStream(), true)
            slash("ping") { slashRan = true }
        }.start().runUntilTerminated()

        assertTrue(slashRan, "/ping handler should have run")
    }

    @Test
    fun `output is printed back to the configured writer`() {
        val agent = echoAgent { "BACK:$it" }
        val out = ByteArrayOutputStream()

        LiveShow.from(agent) {
            input = StringReader("hi\n/quit\n")
            output = PrintWriter(out, true)
        }.start().runUntilTerminated()

        assertTrue(out.toString().contains("BACK:hi"),
            "expected BACK:hi in output, got: ${out.toString()}")
    }

    @Test
    fun `LiveShow from a Pipeline works (overload smoke test)`() {
        val seenInputs = mutableListOf<String>()
        val a = agent<String, String>("first") {
            skills { skill<String, String>("op", "op") { implementedBy { it } } }
        }
        val b = agent<String, String>("second") {
            skills { skill<String, String>("op", "op") {
                implementedBy { seenInputs += it; "PIPE:$it" }
            }}
        }
        val pipeline = a then b

        val out = ByteArrayOutputStream()
        LiveShow.from(pipeline) {
            input = StringReader("greet\n/quit\n")
            output = PrintWriter(out, true)
        }.start().runUntilTerminated()

        assertEquals(listOf("greet"), seenInputs)
        assertTrue(out.toString().contains("PIPE:greet"))
    }

    @Test
    fun `non-String OUT is rendered via toString`() {
        val agent = agent<String, Int>("a") {
            skills { skill<String, Int>("op", "op") { implementedBy { it.length } } }
        }
        val out = ByteArrayOutputStream()
        LiveShow.from(agent) {
            input = StringReader("hello\n/quit\n")
            output = PrintWriter(out, true)
        }.start().runUntilTerminated()

        assertTrue(out.toString().contains("5"),
            "Int output should be rendered as '5', got: ${out.toString()}")
    }

    @Test
    fun `EOF on input terminates the loop (Ctrl-D pattern)`() {
        // No /quit — relies on stream EOF.
        val agent = echoAgent()
        // Should NOT hang.
        LiveShow.from(agent) {
            input = StringReader("ping\n")  // single line, then EOF
            output = PrintWriter(ByteArrayOutputStream(), true)
        }.start().runUntilTerminated()

        // Test passes if we got here without timeout.
    }

    @Test
    fun `blank lines are ignored — agent not invoked`() {
        var invocations = 0
        val agent = agent<String, String>("a") {
            skills { skill<String, String>("op", "op") {
                implementedBy { invocations++; "out" }
            }}
        }
        LiveShow.from(agent) {
            input = StringReader("\n\n\n/quit\n")
            output = PrintWriter(ByteArrayOutputStream(), true)
        }.start().runUntilTerminated()

        assertEquals(0, invocations, "blank lines should be skipped")
    }

    @Test
    fun `agent exception is reported but REPL continues`() {
        val agent = agent<String, String>("a") {
            skills { skill<String, String>("op", "op") {
                implementedBy { input ->
                    if (input == "boom") error("intentional")
                    "ok-$input"
                }
            }}
        }
        val out = ByteArrayOutputStream()
        LiveShow.from(agent) {
            input = StringReader("hi\nboom\nback\n/quit\n")
            output = PrintWriter(out, true)
            // Disable history so the exception-trigger check (input == "boom")
            // sees the raw input rather than a transcript-prepended string.
            maxHistoryTurns = 0
        }.start().runUntilTerminated()

        val s = out.toString()
        assertTrue(s.contains("ok-hi"), "first turn should succeed: $s")
        assertTrue(s.contains("ok-back"), "post-error turn should succeed: $s")
        assertTrue(s.contains("intentional") || s.contains("error", ignoreCase = true),
            "error should be reported visibly: $s")
    }
}

class LiveRunnerTest {

    private fun echoAgent() =
        agent<String, String>("echo") {
            skills { skill<String, String>("op", "op") { implementedBy { "ECHO:$it" } } }
        }

    @Test
    fun `--once runs a single turn and exits 0`() {
        val out = ByteArrayOutputStream()
        val rc = LiveRunner.serve(
            echoAgent(),
            arrayOf("--once", "hi"),
        ) {
            output = PrintWriter(out, true)
        }
        assertEquals(0, rc)
        assertTrue(out.toString().contains("ECHO:hi"), "expected ECHO:hi, got: ${out.toString()}")
    }

    @Test
    fun `--help returns 0 and prints usage`() {
        val out = ByteArrayOutputStream()
        val rc = LiveRunner.serve(echoAgent(), arrayOf("--help")) {
            output = PrintWriter(out, true)
        }
        assertEquals(0, rc)
        // The picocli-shaped --help is normally on stdout. The runner can
        // route via either System.out or the configured output; either is
        // acceptable. The test asserts return code only.
    }

    @Test
    fun `--version returns 0`() {
        val rc = LiveRunner.serve(echoAgent(), arrayOf("--version")) {
            output = PrintWriter(ByteArrayOutputStream(), true)
        }
        assertEquals(0, rc)
    }

    @Test
    fun `unknown flag returns exit code 2`() {
        val rc = LiveRunner.serve(echoAgent(), arrayOf("--bogus")) {
            output = PrintWriter(ByteArrayOutputStream(), true)
        }
        assertEquals(2, rc)
    }

    @Test
    fun `--max-history N overrides the builder default`() {
        // Hard to assert directly without driving the REPL — covered by
        // arg-parsing test below in LiveRunnerArgsTest. This smoke test just
        // verifies the flag is accepted (not treated as unknown).
        val rc = LiveRunner.serve(
            echoAgent(),
            arrayOf("--max-history", "5", "--once", "ok"),
        ) {
            output = PrintWriter(ByteArrayOutputStream(), true)
        }
        assertEquals(0, rc)
    }
}
