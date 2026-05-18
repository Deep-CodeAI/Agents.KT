package agents_engine.runtime

import agents_engine.composition.branch.branch
import agents_engine.composition.loop.loop
import agents_engine.composition.parallel.div
import agents_engine.composition.pipeline.then
import agents_engine.core.agent
import java.io.ByteArrayOutputStream
import java.io.PrintWriter
import java.io.StringReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

// Tests for #1978 — LiveRunner cluster (39 unkilled). Mirrors McpRunner's test
// pattern. Three groups of mutants targeted:
//
// 1. parseArgs branches (lines 159, 172, 173) — flag dispatch, --max-history
//    boundary at 0, builder.maxHistoryTurns side-effect.
// 2. run() control flow (lines 96/97/99/100/111/117/122/138/140) — printHelp
//    invocation on help / error path, println side effects, exit code
//    distinction between happy / error / unknown, shutdown hook registration.
// 3. serve() overload dispatch + lambda return values (serve$lambda$0-11) —
//    each of six serve() overloads must actually invoke its agent/pipeline/etc.
//    in --once mode and surface the result to stdout.
class LiveRunnerCliAndOnceTest {

    // Stub I/O — the runner writes to PrintWriter(out) and reads from Reader(in).
    private fun captureOut(): Pair<PrintWriter, ByteArrayOutputStream> {
        val baos = ByteArrayOutputStream()
        return PrintWriter(baos, true) to baos
    }

    private fun trivialAgent(name: String = "greeter") = agent<String, String>(name) {
        skills { skill<String, String>("greet", "Greet") { implementedBy { "hi $it" } } }
    }

    // ── parseArgs branches ───────────────────────────────────────────────────

    @Test
    fun `parseArgs default empty args produces no errors no help no version no once`() {
        val parsed = LiveRunner.parseArgs(emptyArray()) { /* no configure */ }
        assertTrue(parsed.errors.isEmpty(), "empty args must be valid")
        assertEquals(false, parsed.helpRequested)
        assertEquals(false, parsed.versionRequested)
        assertEquals(null, parsed.once)
    }

    @Test
    fun `parseArgs --help long-form sets helpRequested`() {
        val parsed = LiveRunner.parseArgs(arrayOf("--help")) {}
        assertTrue(parsed.helpRequested, "--help must set helpRequested")
    }

    @Test
    fun `parseArgs -h short-form sets helpRequested`() {
        val parsed = LiveRunner.parseArgs(arrayOf("-h")) {}
        assertTrue(parsed.helpRequested)
    }

    @Test
    fun `parseArgs --version long-form sets versionRequested`() {
        val parsed = LiveRunner.parseArgs(arrayOf("--version")) {}
        assertTrue(parsed.versionRequested)
    }

    @Test
    fun `parseArgs -V short-form sets versionRequested`() {
        val parsed = LiveRunner.parseArgs(arrayOf("-V")) {}
        assertTrue(parsed.versionRequested)
    }

    @Test
    fun `parseArgs --once captures the prompt`() {
        // parseArgs:159 negated conditional — the `when (val a)` flag dispatch.
        // If the --once branch's body is mutated to skip, `once` stays null.
        val parsed = LiveRunner.parseArgs(arrayOf("--once", "hello world")) {}
        assertEquals("hello world", parsed.once)
        assertTrue(parsed.errors.isEmpty())
    }

    @Test
    fun `parseArgs --once at end of args without value reports error`() {
        val parsed = LiveRunner.parseArgs(arrayOf("--once")) {}
        assertTrue(parsed.errors.any { it.contains("--once requires a value") },
            "missing --once value must surface a descriptive error: ${parsed.errors}")
    }

    @Test
    fun `parseArgs --max-history applies value to the builder`() {
        // parseArgs:173 — the `removed call to setMaxHistoryTurns` mutant.
        // If the side-effect line is removed, builder.maxHistoryTurns stays default.
        val parsed = LiveRunner.parseArgs(arrayOf("--max-history", "42")) {}
        assertTrue(parsed.errors.isEmpty(), "valid --max-history must produce no errors: ${parsed.errors}")
        assertEquals(42, parsed.builder.maxHistoryTurns, "--max-history value must be applied to builder")
    }

    @Test
    fun `parseArgs --max-history at end of args without value reports error`() {
        val parsed = LiveRunner.parseArgs(arrayOf("--max-history")) {}
        assertTrue(parsed.errors.any { it.contains("--max-history requires a value") },
            "missing --max-history value must surface error: ${parsed.errors}")
    }

    @Test
    fun `parseArgs --max-history with non-numeric value reports error`() {
        val parsed = LiveRunner.parseArgs(arrayOf("--max-history", "abc")) {}
        assertTrue(parsed.errors.any { it.contains("invalid --max-history value") },
            "non-numeric --max-history must surface error: ${parsed.errors}")
    }

    @Test
    fun `parseArgs --max-history 0 is accepted (boundary at lower bound)`() {
        // parseArgs:172 boundary mutant on `parsed < 0`. Mutated `<= 0` would
        // reject 0 (saying "invalid"); unmutated accepts 0 (saying "valid").
        val parsed = LiveRunner.parseArgs(arrayOf("--max-history", "0")) {}
        assertTrue(parsed.errors.isEmpty(),
            "--max-history 0 is the exact boundary; must be valid. Errors: ${parsed.errors}")
        assertEquals(0, parsed.builder.maxHistoryTurns)
    }

    @Test
    fun `parseArgs --max-history -1 is rejected (just below boundary)`() {
        // Anchors the other side of the boundary.
        val parsed = LiveRunner.parseArgs(arrayOf("--max-history", "-1")) {}
        assertTrue(parsed.errors.any { it.contains("invalid --max-history value") },
            "--max-history -1 below boundary must reject: ${parsed.errors}")
    }

    @Test
    fun `parseArgs unknown flag reports error including flag name`() {
        val parsed = LiveRunner.parseArgs(arrayOf("--nope")) {}
        assertTrue(parsed.errors.any { it.contains("unknown flag") && it.contains("--nope") },
            "unknown flag error must include the flag name: ${parsed.errors}")
    }

    @Test
    fun `parseArgs accumulates multiple errors without bailing on first`() {
        // Catches mutant that early-returns from parseArgs on the first error.
        val parsed = LiveRunner.parseArgs(arrayOf("--bad1", "--max-history", "abc", "--bad2")) {}
        assertTrue(parsed.errors.size >= 3,
            "all three errors must accumulate: ${parsed.errors}")
    }

    // ── --once happy path (kills serve$lambda$0 + run$lambda$3 mutants) ──────

    @Test
    fun `serve --once invokes the agent and prints its output to stdout`() {
        // serve$lambda$0 (line 48) is the `{ agent.invokeSuspend(it) }` lambda
        // bound into run(). PIT "replaced return value with null" would make
        // serve print "null" instead of the agent's actual output.
        val (pw, baos) = captureOut()
        val exit = LiveRunner.serve(trivialAgent(), arrayOf("--once", "world")) {
            output = pw
        }
        assertEquals(0, exit, "--once happy path must return 0")
        val out = baos.toString().trim()
        assertEquals("hi world", out, "agent output must reach stdout, not 'null' or empty")
    }

    @Test
    fun `serve --once with empty prompt still invokes the agent`() {
        val (pw, baos) = captureOut()
        val exit = LiveRunner.serve(trivialAgent(), arrayOf("--once", "")) { output = pw }
        assertEquals(0, exit)
        // "hi " (empty input passed through)
        assertTrue(baos.toString().contains("hi"), "empty prompt should still invoke agent: '${baos.toString().trim()}'")
    }

    @Test
    fun `serve --once for Pipeline overload invokes the pipeline`() {
        // Drives serve$lambda$2 (line 54) — the pipeline.invokeSuspend lambda.
        val pipeline = trivialAgent("a") then agent<String, String>("b") {
            skills { skill<String, String>("op", "transform") { implementedBy { "$it!" } } }
        }
        val (pw, baos) = captureOut()
        val exit = LiveRunner.serve(pipeline, arrayOf("--once", "test")) { output = pw }
        assertEquals(0, exit)
        assertEquals("hi test!", baos.toString().trim(), "pipeline must chain both stages and surface the final output")
    }

    @Test
    fun `serve --once when agent throws returns exit code 2 and prints error`() {
        // run() line 121-123 — the catch block. Kills the int-return mutant
        // (`replaced int return with 0`) and the println mutant.
        val explodingAgent = agent<String, String>("boomer") {
            skills {
                skill<String, String>("boom", "Always throws") {
                    implementedBy { _ -> throw IllegalStateException("kaboom") }
                }
            }
        }
        val (pw, baos) = captureOut()
        val exit = LiveRunner.serve(explodingAgent, arrayOf("--once", "x")) { output = pw }
        assertEquals(2, exit, "agent exception must return exit 2, not 0")
        val out = baos.toString()
        assertTrue(out.contains("kaboom"), "error message must reach stdout: '$out'")
    }

    // ── help / version output (kills printHelp + println mutants) ────────────

    @Test
    fun `serve --help returns exit 0 and prints help banner with version`() {
        // run() lines 96 + printHelp (line 190+). Kills removed-call mutants
        // on printHelp and the println inside printHelp.
        val (pw, baos) = captureOut()
        val exit = LiveRunner.serve(trivialAgent(), arrayOf("--help")) { output = pw }
        assertEquals(0, exit)
        val out = baos.toString()
        assertTrue(out.contains("Agents.KT"), "help must include 'Agents.KT' banner: '$out'")
        assertTrue(out.contains("--once"), "help must document --once flag: '$out'")
        assertTrue(out.contains("--max-history"), "help must document --max-history flag")
        assertTrue(out.contains("--help"), "help must document --help flag")
    }

    @Test
    fun `serve --version returns exit 0 and prints version line to stdout`() {
        // run() line 97 — `out.println("Agents.KT $VERSION")`.
        // Kills the println-removal mutant.
        val (pw, baos) = captureOut()
        val exit = LiveRunner.serve(trivialAgent(), arrayOf("--version")) { output = pw }
        assertEquals(0, exit)
        val out = baos.toString()
        assertTrue(out.contains("Agents.KT"), "version line must mention 'Agents.KT': '$out'")
    }

    // ── error / unknown flag (kills exit code mutants + println + printHelp) ─

    @Test
    fun `serve unknown flag returns exit 2 and prints error PLUS usage`() {
        // run() lines 98-101 — error iteration println + printHelp invocation.
        val (pw, baos) = captureOut()
        val exit = LiveRunner.serve(trivialAgent(), arrayOf("--nonsense")) { output = pw }
        assertEquals(2, exit)
        val out = baos.toString()
        assertTrue(out.contains("error:"), "errors must be prefixed with 'error:': '$out'")
        assertTrue(out.contains("--nonsense"), "the offending flag should appear in the error: '$out'")
        assertTrue(out.contains("--once"), "usage should follow the error line (kills printHelp removal): '$out'")
    }

    @Test
    fun `serve unknown flag exit code is distinct from happy path`() {
        // Catches `replaced int return with 0` on the error path.
        val (pw, _) = captureOut()
        val errExit = LiveRunner.serve(trivialAgent(), arrayOf("--nonsense")) { output = pw }
        assertNotEquals(0, errExit, "error path must NOT return 0 (happy-path exit code)")
    }

    // ── Phase 2: --once tests for the remaining 4 serve() overloads ──────────
    // Each kills its serve$lambda$N "replaced return with null" mutant plus
    // the `replaced int return with 0` mutant on the overload's serve() method.

    @Test
    fun `serve --once for Forum overload invokes participants and captain`() {
        val a = agent<String, String>("a") {
            skills { skill<String, String>("op", "_") { implementedBy { "from-a" } } }
        }
        val b = agent<String, String>("b") {
            skills { skill<String, String>("op", "_") { implementedBy { "from-b" } } }
        }
        val captain = agent<String, String>("captain") {
            skills { skill<String, String>("op", "_") { implementedBy { "captain-saw:$it" } } }
        }
        val forum = agents_engine.composition.forum.forum<String, String> {
            participant(a)
            participant(b)
            captain(captain)
        }
        val (pw, baos) = captureOut()
        val exit = LiveRunner.serve(forum, arrayOf("--once", "trigger")) { output = pw }
        assertEquals(0, exit)
        val out = baos.toString().trim()
        assertTrue(out.startsWith("captain-saw:"), "captain output must reach stdout: '$out'")
    }

    @Test
    fun `serve --once for Parallel overload invokes all branches`() {
        val a = agent<String, String>("a") {
            skills { skill<String, String>("op", "_") { implementedBy { "a:$it" } } }
        }
        val b = agent<String, String>("b") {
            skills { skill<String, String>("op", "_") { implementedBy { "b:$it" } } }
        }
        val parallel = a / b
        val (pw, baos) = captureOut()
        val exit = LiveRunner.serve(parallel, arrayOf("--once", "x")) { output = pw }
        assertEquals(0, exit)
        val out = baos.toString()
        assertTrue(out.contains("a:x") && out.contains("b:x"),
            "both branches must reach stdout: '$out'")
    }

    @Test
    fun `serve --once for Loop overload runs the loop once and terminates`() {
        val counter = agent<String, String>("counter") {
            skills { skill<String, String>("op", "_") { implementedBy { "step:$it" } } }
        }
        // Single-step loop: next returns null after the first iteration.
        val loop = counter.loop<String, String> { _ -> null }
        val (pw, baos) = captureOut()
        val exit = LiveRunner.serve(loop, arrayOf("--once", "go")) { output = pw }
        assertEquals(0, exit)
        assertEquals("step:go", baos.toString().trim())
    }

    @Test
    fun `serve --once for Branch overload routes through onElse arm`() {
        val source = agent<String, String>("source") {
            skills { skill<String, String>("op", "_") { implementedBy { "src:$it" } } }
        }
        val handler = agent<String, String>("handler") {
            skills { skill<String, String>("op", "_") { implementedBy { "handled:$it" } } }
        }
        val branch = source.branch<String, String, String> {
            onElse then handler
        }
        val (pw, baos) = captureOut()
        val exit = LiveRunner.serve(branch, arrayOf("--once", "trigger")) { output = pw }
        assertEquals(0, exit)
        assertTrue(baos.toString().trim().startsWith("handled:"),
            "branch onElse arm must route source output through handler: '${baos.toString().trim()}'")
    }
}
