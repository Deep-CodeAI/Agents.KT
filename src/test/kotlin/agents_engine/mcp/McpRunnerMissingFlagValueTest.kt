package agents_engine.mcp

import agents_engine.core.agent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Tests for #889 — McpRunner.resolveConfig branches uncovered by the existing
// McpRunnerTest / McpRunnerMutationTest. Targets the "flag-with-missing-value"
// error paths in resolveConfig (McpRunner.kt:110-111 + :120-122) and the
// multi-error accumulation contract.
class McpRunnerMissingFlagValueTest {

    private fun trivial() = agent<String, String>("greeter") {
        skills { skill<String, String>("greet", "Greets") { implementedBy { "hi $it" } } }
    }

    @Test
    fun `--port at end of args with no value produces a descriptive error`() {
        val cfg = McpRunner.resolveConfig(arrayOf("--port"))
        assertTrue(cfg.errors.isNotEmpty(), "--port with no value must surface an error")
        assertTrue(
            cfg.errors.any { it.contains("--port requires a value", ignoreCase = false) },
            "error message should match exactly: ${cfg.errors}",
        )
    }

    @Test
    fun `--expose at end of args with no value produces a descriptive error`() {
        val cfg = McpRunner.resolveConfig(arrayOf("--expose"))
        assertTrue(cfg.errors.isNotEmpty(), "--expose with no value must surface an error")
        assertTrue(
            cfg.errors.any { it.contains("--expose requires a skill name") },
            "error message should match exactly: ${cfg.errors}",
        )
    }

    @Test
    fun `resolveConfig accumulates multiple errors instead of bailing on first`() {
        // Catches the mutant that replaces `errors += ...` with `return errors` or breaks the loop.
        val cfg = McpRunner.resolveConfig(arrayOf("--port", "--unknown-flag", "--expose"))
        // Three distinct errors expected:
        // 1. --port at end (the next arg --unknown-flag is consumed as its value, fails int parse)
        // 2. --unknown-flag is unknown (depending on consumption order this may collapse)
        // 3. --expose at end of args, missing skill name
        assertTrue(
            cfg.errors.size >= 2,
            "should accumulate ≥2 errors, got ${cfg.errors.size}: ${cfg.errors}",
        )
    }

    @Test
    fun `--port followed by --expose consumes --expose as the port value and fails`() {
        // Documents the actual parser behavior: a missing-value flag greedily consumes
        // the next arg. Mostly a regression assertion — the parser is intentionally
        // simple (no lookahead for "is the next token a flag?"). If we ever add that
        // smarter handling, this test will flip and that's the signal to revisit.
        val cfg = McpRunner.resolveConfig(arrayOf("--port", "--expose", "greet"))
        assertTrue(
            cfg.errors.any { it.contains("invalid port value") },
            "current parser: --expose consumed as port value → invalid port. " +
                "If this fails, the parser grew lookahead — update or split this test. " +
                "Errors: ${cfg.errors}",
        )
    }

    @Test
    fun `valid args after an error still parse without crashing`() {
        // Catches the mutant that early-returns from resolveConfig on the first error.
        val cfg = McpRunner.resolveConfig(arrayOf("--port", "abc", "--expose", "greet"))
        assertTrue(
            cfg.errors.any { it.contains("invalid port value") },
            "should report invalid port",
        )
        assertEquals(listOf("greet"), cfg.exposeNames, "valid --expose after error must still be honored")
    }
}
