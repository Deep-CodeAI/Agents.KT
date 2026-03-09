package agents_engine.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * KnowledgeTool must carry a description so the LLM can decide
 * WHICH entries to call before calling them.
 *
 * Without a description the LLM only has the key name ("style-guide",
 * "examples") and must guess at the content — unreliable for anything
 * beyond obvious naming conventions.
 *
 * With a description the LLM receives a menu like:
 *   - style-guide: "Preferred coding style — immutability, naming, formatting"
 *   - examples:    "Concrete input/output pairs for few-shot prompting"
 * and can make an informed tool-call decision.
 */
class KnowledgeToolDescriptionTest {

    @Test
    fun `KnowledgeTool has description alongside name`() {
        val tool = KnowledgeTool(
            name = "style-guide",
            description = "Preferred coding style rules for this skill",
            call = { "Use active voice." }
        )
        assertEquals("style-guide", tool.name)
        assertEquals("Preferred coding style rules for this skill", tool.description)
        assertEquals("Use active voice.", tool.call())
    }

    @Test
    fun `knowledge entry accepts description next to key`() {
        val s = skill<Int, Int>("add", "Adds one") {
            knowledge("rules", "Constraints the result must satisfy") { "Always positive" }
            implementedBy { it + 1 }
        }
        val tool = s.knowledgeTools().single()
        assertEquals("rules", tool.name)
        assertEquals("Constraints the result must satisfy", tool.description)
        assertEquals("Always positive", tool.call())
    }

    @Test
    fun `all knowledge tools carry their descriptions`() {
        val s = skill<String, String>("process", "Processes text") {
            knowledge("style", "Voice and tone guidelines") { "Active voice only" }
            knowledge("examples", "Concrete input/output pairs for few-shot prompting") { "In: hello → Out: HELLO" }
            knowledge("checklist", "Self-verification steps before returning output") { "1. Check length\n2. Check tone" }
            implementedBy { it }
        }
        val tools = s.knowledgeTools().associateBy { it.name }
        assertEquals("Voice and tone guidelines", tools["style"]!!.description)
        assertEquals("Concrete input/output pairs for few-shot prompting", tools["examples"]!!.description)
        assertEquals("Self-verification steps before returning output", tools["checklist"]!!.description)
    }

    @Test
    fun `toLlmContext includes knowledge descriptions as labels`() {
        val s = skill<String, String>("summarize", "Summarizes text") {
            knowledge("style", "Voice and tone guidelines") { "Active voice only" }
            implementedBy { it }
        }
        val ctx = s.toLlmContext()
        println("Description")
        val description = s.toLlmDescription()
        println(ctx)
        println(description)
        assertTrue("Voice and tone guidelines" in ctx)
        assertTrue("Active voice only" in ctx)
    }

    @Test
    fun `knowledge description is separate from knowledge value`() {
        val s = skill<Int, Int>("compute", "Computes result") {
            knowledge("rules", "Mathematical constraints on the output") { "Must be >= 0" }
            implementedBy { it }
        }
        val tool = s.knowledgeTools().single()
        // description tells LLM what this tool contains
        assertEquals("Mathematical constraints on the output", tool.description)
        // call() returns the actual context content
        assertEquals("Must be >= 0", tool.call())
    }
}
