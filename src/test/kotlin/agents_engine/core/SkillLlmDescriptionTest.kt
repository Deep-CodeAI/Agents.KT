package agents_engine.core

import agents_engine.generation.Generable
import agents_engine.generation.Guide
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Generable("Raw text document submitted for processing")
data class Document(
    @Guide("The full text content") val text: String,
)

@Generable("A concise single-sentence summary")
data class Summary(
    @Guide("The summary sentence") val sentence: String,
)

/**
 * Convention-over-configuration: toLlmDescription() auto-generates a fully
 * descriptive prose sentence from what's already on the skill — no extra
 * methods or annotations required.
 *
 * Default template:
 *   Skill "${name}". As input takes: ${IN}. As output produces: ${OUT}.
 *   ${description}.
 *   Has following knowledge parts: ${key} (${entryDescription}), ...
 *
 * The user can override it with llmDescription("custom text") if needed.
 */
class SkillLlmDescriptionTest {

    @Test
    fun `default description contains skill name in prose form`() {
        val s = skill<String, Int>("count-chars", "Counts characters in a string") {
            implementedBy { it.length }
        }
        assertTrue("count-chars" in s.toLlmDescription())
    }

    @Test
    fun `default description states what input type is taken`() {
        val s = skill<String, Int>("count-chars", "Counts characters in a string") {
            implementedBy { it.length }
        }
        val desc = s.toLlmDescription()
        assertTrue("String" in desc)
        assertTrue("input" in desc.lowercase())
    }

    @Test
    fun `default description states what output type is produced`() {
        val s = skill<String, Int>("count-chars", "Counts characters in a string") {
            implementedBy { it.length }
        }
        val desc = s.toLlmDescription()
        assertTrue("Int" in desc)
        assertTrue("output" in desc.lowercase() || "produces" in desc.lowercase())
    }

    @Test
    fun `default description includes the skill description text`() {
        val s = skill<String, Int>("count-chars", "Counts characters in a string") {
            implementedBy { it.length }
        }
        assertTrue("Counts characters in a string" in s.toLlmDescription())
    }

    @Test
    fun `default description lists knowledge parts with their descriptions`() {
        val s = skill<String, String>("summarize", "Summarizes a document") {
            knowledge("style",    "Voice and tone guidelines")         { "Active voice only" }
            knowledge("examples", "Concrete input/output pairs")       { "In: long text → Out: short" }
            implementedBy { it }
        }
        val desc = s.toLlmDescription()
        println(desc)
        assertTrue("style" in desc)
        assertTrue("Voice and tone guidelines" in desc)
        assertTrue("examples" in desc)
        assertTrue("Concrete input/output pairs" in desc)
    }

    @Test
    fun `default description omits knowledge section when no knowledge`() {
        val s = skill<Int, Int>("double", "Doubles the value") { implementedBy { it * 2 } }
        val desc = s.toLlmDescription()
        assertFalse("**Knowledge:**" in desc)
    }

    @Test
    fun `full description reads as natural prose`() {
        val s = skill<String, String>("summarize", "Summarizes a long document into one sentence") {
            knowledge("style",    "Voice and tone guidelines")   { "Active voice only" }
            knowledge("checklist","Steps to verify the output")  { "Check length. Check tone." }
            implementedBy { it }
        }
        val desc = s.toLlmDescription()
        println(desc)
        // reads like a sentence, not a code dump
        assertTrue("summarize" in desc)
        assertTrue("String" in desc)
        assertTrue("Summarizes a long document into one sentence" in desc)
        assertTrue("style" in desc)
        assertTrue("Voice and tone guidelines" in desc)
        assertTrue("checklist" in desc)
        assertTrue("Steps to verify the output" in desc)
    }

    @Test
    fun `llmDescription override replaces auto-generated text`() {
        val s = skill<String, String>("summarize", "Summarizes a document") {
            llmDescription("Custom description for this skill")
            implementedBy { it }
        }
        val desc = s.toLlmDescription()
        assertTrue("Custom description for this skill" in desc)
        // auto-generated parts are gone
        assertFalse("As input takes" in desc)
        assertFalse("As output produces" in desc)
    }

    @Test
    fun `knowledge entry with no description still appears in knowledge list`() {
        val s = skill<String, String>("process", "Processes input") {
            knowledge("rules") { "Always return non-empty string" }
            implementedBy { it }
        }
        val desc = s.toLlmDescription()
        assertTrue("rules" in desc)
    }

    @Test
    fun `toLlmDescription includes @Generable description for input type`() {
        val s = skill<Document, Summary>("summarize", "Summarizes text to one sentence") {
            knowledge("style", "Voice and tone guidelines") { "Active voice only" }
            implementedBy { Summary(it.text) }
        }
        val desc = s.toLlmDescription()
        println(desc)
        assertTrue("Raw text document submitted for processing" in desc)
    }

    @Test
    fun `toLlmDescription includes field names of @Generable input type`() {
        val s = skill<Document, Summary>("summarize", "Summarizes text to one sentence") {
            implementedBy { Summary(it.text) }
        }
        val desc = s.toLlmDescription()
        assertTrue("text" in desc)
    }

    @Test
    fun `toLlmDescription includes field Guide descriptions of @Generable input type`() {
        val s = skill<Document, Summary>("summarize", "Summarizes text to one sentence") {
            implementedBy { Summary(it.text) }
        }
        val desc = s.toLlmDescription()
        assertTrue("The full text content" in desc)
    }

    @Test
    fun `toLlmDescription includes @Generable description for output type`() {
        val s = skill<Document, Summary>("summarize", "Summarizes text to one sentence") {
            implementedBy { Summary(it.text) }
        }
        val desc = s.toLlmDescription()
        assertTrue("A concise single-sentence summary" in desc)
    }

    @Test
    fun `toLlmDescription includes field names of @Generable output type`() {
        val s = skill<Document, Summary>("summarize", "Summarizes text to one sentence") {
            implementedBy { Summary(it.text) }
        }
        val desc = s.toLlmDescription()
        assertTrue("sentence" in desc)
    }

    @Test
    fun `toLlmDescription includes field Guide descriptions of @Generable output type`() {
        val s = skill<Document, Summary>("summarize", "Summarizes text to one sentence") {
            implementedBy { Summary(it.text) }
        }
        val desc = s.toLlmDescription()
        assertTrue("The summary sentence" in desc)
    }

    @Test
    fun `toLlmContext includes @Generable type shapes alongside knowledge`() {
        val s = skill<Document, Summary>("summarize", "Summarizes text to one sentence") {
            knowledge("style", "Voice and tone guidelines") { "Active voice only" }
            implementedBy { Summary(it.text) }
        }
        val ctx = s.toLlmContext()
        println("<<<<")
        println(ctx)
        println(">>>>>")
        assertTrue("Raw text document submitted for processing" in ctx)
        assertTrue("The full text content" in ctx)
        assertTrue("A concise single-sentence summary" in ctx)
        assertTrue("The summary sentence" in ctx)
        assertTrue("Voice and tone guidelines" in ctx)
        assertTrue("Active voice only" in ctx)
    }
}
