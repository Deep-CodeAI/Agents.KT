package agents_engine.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Tests for Agent cluster (16 unkilled — 5 are PIT noise: 3 lambda$0
// inline-attribution + 2 outside-file artifacts at L 597/L 604; 11 substantive).
//
// Focus: property-getter mutants (NullReturnVals on errorListener,
// routerRationale, defaultToolErrorHandler; PrimitiveReturns on confidence
// threshold; BooleanReturns on frozen) + skillSelectionConfidenceThreshold
// setter boundary + describePrompt length boundary at 80.
class AgentCoverageTest {

    // ── property getters: listener round-trip via internal-property access ───

    @Test
    fun `errorListener fires when an agentic invocation throws`() {
        // Kills NullReturnVals on getErrorListener — the listener must be
        // non-null after onError {} runs, AND the post-invocation propagation
        // path must INVOKE it. Both proven by observation.
        val captured = mutableListOf<Throwable>()
        val failing = agent<String, String>("failing") {
            skills {
                skill<String, String>("s") {
                    implementedBy { _: String -> error("boom") }
                }
            }
            onError { e -> captured.add(e) }
        }
        val ex = assertFails { failing("anything") }
        assertEquals(1, captured.size, "errorListener must have fired before exception propagated")
        assertEquals("boom", captured[0].message)
        assertNotNull(ex)
    }

    @Test
    fun `routerRationaleListener field reflects DSL configuration`() {
        // Kills NullReturnVals on getRouterRationaleListener via the
        // @PublishedApi internal property read (same-package access).
        val captured = mutableListOf<String>()
        val a = agent<String, String>("a") {
            skills { skill<String, String>("s") { implementedBy { it } } }
            routerRationale { r -> captured.add(r) }
        }
        // The getter must return the lambda we set.
        val listener = a.routerRationaleListener
        assertNotNull(listener,
            "routerRationale {} must populate the listener; null-return mutant fails here")
        listener.invoke("test-rationale")
        assertEquals(listOf("test-rationale"), captured,
            "the stored lambda is the one we registered (no swap)")
    }

    @Test
    fun `routerRationaleListener is null when no routerRationale block was declared`() {
        val a = agent<String, String>("a") {
            skills { skill<String, String>("s") { implementedBy { it } } }
        }
        assertNull(a.routerRationaleListener,
            "no DSL block → listener stays null (default)")
    }

    @Test
    fun `defaultToolErrorHandler null by default and populated via tools defaults block`() {
        // Kills NullReturnVals on getDefaultToolErrorHandler — verify default
        // null AND the populated-via-DSL case.
        val plain = agent<String, String>("plain") {
            skills { skill<String, String>("s") { implementedBy { it } } }
        }
        assertNull(plain.defaultToolErrorHandler,
            "no tools{defaults{onError}} block → default handler null")

        val withHandler = agent<String, String>("with") {
            skills { skill<String, String>("s") { implementedBy { it } } }
            tools {
                defaults {
                    onError {
                        executionError { retry(1) }
                    }
                }
            }
        }
        assertNotNull(withHandler.defaultToolErrorHandler,
            "tools{defaults{onError}} block must populate defaultToolErrorHandler; null-return mutant fails here")
    }

    @Test
    fun `skillSelectionConfidenceThreshold getter returns configured Double`() {
        // Kills PrimitiveReturnsMutator on getSkillSelectionConfidenceThreshold
        // (replaces return with 0.0). Default is 0.6; configure 0.85.
        val a = agent<String, String>("a") {
            skills { skill<String, String>("s") { implementedBy { it } } }
            skillSelectionConfidenceThreshold(0.85)
        }
        assertEquals(0.85, a.skillSelectionConfidenceThreshold,
            "getter returns configured threshold, not 0.0 (PrimitiveReturns mutant)")
    }

    @Test
    fun `skillSelectionConfidenceThreshold defaults to 0_6`() {
        val a = agent<String, String>("a") {
            skills { skill<String, String>("s") { implementedBy { it } } }
        }
        assertEquals(0.6, a.skillSelectionConfidenceThreshold,
            "default threshold 0.6 (kills PrimitiveReturns mutant that returns 0.0)")
    }

    @Test
    fun `agent frozen flag becomes true after validate`() {
        // Kills BooleanFalseReturnVals on getFrozen — the flag IS set to true
        // by validate(). Direct property read via @PublishedApi internal.
        val a = agent<String, String>("a") {
            skills { skill<String, String>("s") { implementedBy { it } } }
        }
        assertTrue(a.frozen, "post-validate agent must be frozen (kills BooleanFalseReturnVals)")
    }

    @Test
    fun `agent post-freeze structural mutation throws`() {
        // Kills BooleanTrueReturnVals on getFrozen — if the getter always
        // returned true, the pre-validate setters would throw too. Build an
        // agent, then verify a post-validate structural setter throws.
        val a = agent<String, String>("a") {
            skills { skill<String, String>("s") { implementedBy { it } } }
        }
        val ex = assertFails { a.skillSelectionConfidenceThreshold(0.5) }
        assertNotNull(ex, "post-freeze structural mutator must throw")
    }

    // ── skillSelectionConfidenceThreshold setter boundary (L 231) ────────────

    @Test
    fun `skillSelectionConfidenceThreshold accepts boundary 0_0`() {
        // Kills ConditionalsBoundaryMutator on `threshold in 0.0..1.0` left side.
        val a = agent<String, String>("a") {
            skills { skill<String, String>("s") { implementedBy { it } } }
            skillSelectionConfidenceThreshold(0.0)
        }
        assertEquals(0.0, a.skillSelectionConfidenceThreshold,
            "0.0 is inclusive; boundary mutant flipping `>=` to `>` would reject it")
    }

    @Test
    fun `skillSelectionConfidenceThreshold accepts boundary 1_0`() {
        // Kills ConditionalsBoundaryMutator on `threshold in 0.0..1.0` right side.
        val a = agent<String, String>("a") {
            skills { skill<String, String>("s") { implementedBy { it } } }
            skillSelectionConfidenceThreshold(1.0)
        }
        assertEquals(1.0, a.skillSelectionConfidenceThreshold,
            "1.0 is inclusive; boundary mutant flipping `<=` to `<` would reject it")
    }

    @Test
    fun `skillSelectionConfidenceThreshold rejects just-below-zero and just-above-one`() {
        val build: (Double) -> Unit = { t ->
            agent<String, String>("a") {
                skills { skill<String, String>("s") { implementedBy { it } } }
                skillSelectionConfidenceThreshold(t)
            }
        }
        assertNotNull(assertFails { build(-0.01) }, "-0.01 outside boundary must throw")
        assertNotNull(assertFails { build(1.01) }, "1.01 outside boundary must throw")
    }

    // ── describePrompt length boundary (L 467: prompt.length <= 80) ──────────

    @Test
    fun `agent toString shows prompt verbatim at exactly 80 chars`() {
        // L 467: `prompt.length <= 80 -> prompt`. The 80-char case must
        // render verbatim. Boundary mutant flipping `<=` to `<` would
        // truncate the 80-char case.
        val exactly80 = "x".repeat(80)
        val a = agent<String, String>("a") {
            prompt(exactly80)
            skills { skill<String, String>("s") { implementedBy { it } } }
        }
        val rendered = a.describe()
        assertTrue(rendered.contains(exactly80),
            "80-char prompt must render verbatim (inclusive boundary)")
        assertFalse(rendered.contains("$exactly80..."),
            "no ellipsis at exactly 80 chars")
    }

    @Test
    fun `agent toString truncates prompt at 81 chars to first 77 plus ellipsis`() {
        val prompt81 = "x".repeat(81)
        val a = agent<String, String>("a") {
            prompt(prompt81)
            skills { skill<String, String>("s") { implementedBy { it } } }
        }
        val rendered = a.describe()
        assertTrue(rendered.contains("${"x".repeat(77)}..."),
            "81-char prompt must render as first 77 chars + '...'")
    }

    @Test
    fun `agent toString shows (none) for blank prompt`() {
        // First branch of describePrompt's `when`: prompt.isBlank() -> "(none)".
        val a = agent<String, String>("a") {
            skills { skill<String, String>("s") { implementedBy { it } } }
        }
        val rendered = a.describe()
        assertTrue(rendered.contains("(none)"),
            "blank prompt → '(none)': $rendered")
    }
}
