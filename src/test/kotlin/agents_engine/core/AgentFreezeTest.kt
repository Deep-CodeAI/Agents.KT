package agents_engine.core

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Tests for #697 — Agent structural mutators throw post-construction so the
 * agent's contract (skills, tools, memory, model, budget, prompt, error
 * handlers, routing config) can't drift after `agent { }` returns.
 *
 * Listeners (onToolUse, onKnowledgeUsed, onSkillChosen, routerRationale) stay
 * mutable — they're observers, not structural.
 */
class AgentFreezeTest {

    private fun trivial() = agent<String, String>("a") {
        skills { skill<String, String>("s", "stub") { implementedBy { it } } }
    }

    private fun assertFrozenError(block: () -> Unit) {
        try { block(); fail("expected freeze rejection") }
        catch (e: IllegalStateException) {
            assertTrue(
                e.message!!.contains("frozen", ignoreCase = true),
                "must explain why: ${e.message}",
            )
        }
    }

    @Test fun `skills block throws after construction`() {
        val a = trivial()
        assertFrozenError { a.skills { skill<String, String>("late", "stub") { implementedBy { it } } } }
    }

    @Test fun `tools block throws after construction`() {
        val a = trivial()
        assertFrozenError { a.tools { tool("late", "x") { _ -> "ok" } } }
    }

    @Test fun `memory throws after construction`() {
        val a = trivial()
        assertFrozenError { a.memory(MemoryBank()) }
    }

    @Test fun `model throws after construction`() {
        val a = trivial()
        assertFrozenError { a.model { ollama("late") } }
    }

    @Test fun `budget throws after construction`() {
        val a = trivial()
        assertFrozenError { a.budget { maxTurns = 100 } }
    }

    @Test fun `prompt throws after construction`() {
        val a = trivial()
        assertFrozenError { a.prompt("late prompt") }
    }

    @Test fun `onToolError throws after construction`() {
        val a = trivial()
        assertFrozenError { a.onToolError("anything") { } }
    }

    @Test fun `skillSelectionConfidenceThreshold throws after construction`() {
        val a = trivial()
        assertFrozenError { a.skillSelectionConfidenceThreshold(0.9) }
    }

    @Test fun `skillSelection throws after construction`() {
        val a = trivial()
        assertFrozenError { a.skillSelection { _ -> "s" } }
    }

    @Test fun `listeners (onToolUse, onSkillChosen, etc) remain settable post-construction (regression)`() {
        // Observers should NOT be frozen — they're added for tracing / instrumentation
        // and don't change the agent's contract.
        val a = trivial()
        a.onToolUse { _, _, _ -> }      // no throw
        a.onSkillChosen { _ -> }        // no throw
        a.onKnowledgeUsed { _, _ -> }   // no throw
        a.routerRationale { _ -> }      // no throw
    }
}
