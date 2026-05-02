package agents_engine.core

import agents_engine.model.ToolDef
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

    @Test fun `registerTool throws after construction (closes mcp post-construction bypass #708)`() {
        // #708: Agent.mcp { } is a public extension that calls registerTool(td)
        // to install MCP tools. Without freeze on registerTool, post-construction
        // mcp { } silently mutates the registry — defeating "frozen after
        // construction." Guarding registerTool closes both the direct API and
        // the mcp DSL path.
        val a = trivial()
        assertFrozenError {
            a.registerTool(ToolDef(name = "late_tool", executor = { "x" }))
        }
    }

    @Test fun `registerBuiltInTool remains unguarded for runtime composition (regression)`() {
        // Forum's captain-rotation flow registers/unregisters forum_return at
        // runtime via the built-in path. That MUST stay open even after freeze.
        val a = trivial()
        a.registerBuiltInTool(ToolDef(name = "memory_read", executor = { "x" }))   // no throw
        assertTrue("memory_read" in a.toolMap)
        a.unregisterTool("memory_read")                                            // no throw
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
