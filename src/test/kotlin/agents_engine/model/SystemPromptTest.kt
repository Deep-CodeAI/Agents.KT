package agents_engine.model

import agents_engine.core.agent
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #3406 — pins the system-prompt builder extracted from `executeAgentic`'s inline `buildString` into
 * [buildSystemPrompt]. Previously only reachable through a full invocation; now directly testable.
 */
class SystemPromptTest {

    private val skill = agent<String, String>("a") {
        skills { skill<String, String>("s", "does a thing") { implementedBy { it } } }
    }.skills.values.first()

    @Test
    fun `lists each tool name and starts with the effective prompt`() {
        val tools = listOf(
            ToolDef(name = "safe", description = "ok") { _ -> "x" },
            ToolDef(name = "web", description = "fetch") { _ -> "x" },
        )
        val prompt = buildSystemPrompt("SYSTEM", skill, tools, knowledgeToolDefs = emptyList())
        assertTrue(prompt.startsWith("SYSTEM"), "starts with the effective prompt")
        assertTrue("- safe" in prompt && "- web" in prompt, "lists tool names: $prompt")
    }

    @Test
    fun `includes the untrusted-tools security preamble only when a tool is untrusted`() {
        val safe = ToolDef(name = "safe", description = "ok") { _ -> "x" }
        val untrusted = ToolDef(name = "web", description = "fetch", untrustedOutput = true) { _ -> "x" }
        assertTrue("[Security]" in buildSystemPrompt("s", skill, listOf(safe, untrusted), emptyList()))
        assertFalse("[Security]" in buildSystemPrompt("s", skill, listOf(safe), emptyList()))
    }
}
