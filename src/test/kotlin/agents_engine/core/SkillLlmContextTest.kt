package agents_engine.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * How a skill exposes itself to an LLM.
 *
 * Two models explored:
 *
 * 1. **All-at-once** — everything (description + all knowledge) is loaded into
 *    context before the LLM runs. Simple, token-heavier.
 *    API: skill.toLlmContext()
 *
 * 2. **Tools model** — LLM receives only the skill description upfront;
 *    knowledge entries are individually callable by key, like tool calls.
 *    LLM decides which knowledge it needs.
 *    API: skill.knowledge["key"]!!()  (already works — each entry is a () -> String)
 *         skill.knowledgeTools()       (list of KnowledgeTool descriptors)
 */
class SkillLlmContextTest {

    // ─── toLlmDescription: name + types + description ───────────────────────

    @Test
    fun `toLlmDescription contains skill name`() {
        val s = skill<Int, Int>("add-one", "Adds one to the integer input") { implementedBy { it + 1 } }
        assertTrue("add-one" in s.toLlmDescription())
    }

    @Test
    fun `toLlmDescription contains description text`() {
        val s = skill<Int, Int>("add-one", "Adds one to the integer input") { implementedBy { it + 1 } }
        assertTrue("Adds one to the integer input" in s.toLlmDescription())
    }

    @Test
    fun `toLlmDescription contains input and output type names`() {
        val s = skill<String, Int>("count", "Counts characters in a string") { implementedBy { it.length } }
        val desc = s.toLlmDescription()
        assertTrue("String" in desc)
        assertTrue("Int" in desc)
    }

    // ─── Model 1: all-at-once ────────────────────────────────────────────────

    @Test
    fun `toLlmContext includes description`() {
        val s = skill<String, String>("summarize", "Summarizes text to one sentence") {
            implementedBy { it }
        }
        assertTrue("Summarizes text to one sentence" in s.toLlmContext())
    }

    @Test
    fun `toLlmContext includes all knowledge entries`() {
        val s = skill<String, String>("summarize", "Summarizes text to one sentence") {
            knowledge("style") { "Use active voice" }
            knowledge("length") { "Max 20 words" }
            implementedBy { it }
        }
        val ctx = s.toLlmContext()
        assertTrue("Use active voice" in ctx)
        assertTrue("Max 20 words" in ctx)
    }

    @Test
    fun `toLlmContext includes knowledge entry keys`() {
        val s = skill<String, String>("summarize", "Summarizes text") {
            knowledge("style") { "Use active voice" }
            knowledge("examples") { "Input: long text → Output: short text" }
            implementedBy { it }
        }
        val ctx = s.toLlmContext()
        assertTrue("style" in ctx)
        assertTrue("examples" in ctx)
    }

    @Test
    fun `toLlmContext with no knowledge omits knowledge section`() {
        val s = skill<Int, Int>("noop", "Returns input unchanged") { implementedBy { it } }
        val ctx = s.toLlmContext()
        assertTrue("Returns input unchanged" in ctx)
        assertFalse("**Knowledge:**" in ctx)
    }

    @Test
    fun `toLlmContext evaluates knowledge lazily at call time`() {
        var callCount = 0
        val s = skill<Int, Int>("dynamic", "Uses dynamic knowledge") {
            knowledge("counter") { callCount++; "call #$callCount" }
            implementedBy { it }
        }
        assertEquals(0, callCount)
        s.toLlmContext()
        assertEquals(1, callCount)
        s.toLlmContext()
        assertEquals(2, callCount)
    }

    // ─── Model 2: knowledge as tools ────────────────────────────────────────

    @Test
    fun `knowledgeTools returns one tool per knowledge entry`() {
        val s = skill<String, String>("process", "Processes text") {
            knowledge("style") { "Use active voice" }
            knowledge("rules") { "Never exceed 100 chars" }
            implementedBy { it }
        }
        assertEquals(2, s.knowledgeTools().size)
    }

    @Test
    fun `each knowledgeTool has name and callable`() {
        val s = skill<String, String>("process", "Processes text") {
            knowledge("style") { "Use active voice" }
            implementedBy { it }
        }
        val tool = s.knowledgeTools().single()
        assertEquals("style", tool.name)
        assertEquals("Use active voice", tool.call())
    }

    @Test
    fun `knowledgeTool call is lazy — invoked only when called`() {
        var called = false
        val s = skill<Int, Int>("lazy", "Lazy skill") {
            knowledge("heavy") { called = true; "expensive data" }
            implementedBy { it }
        }
        val tool = s.knowledgeTools().single()
        assertFalse(called)
        tool.call()
        assertTrue(called)
    }

    @Test
    fun `knowledgeTools returns empty list when no knowledge entries`() {
        val s = skill<Int, Int>("bare", "No knowledge") { implementedBy { it } }
        assertTrue(s.knowledgeTools().isEmpty())
    }

    @Test
    fun `skill with knowledge tools can still be executed normally`() {
        val s = skill<Int, Int>("add", "Adds two") {
            knowledge("note") { "answer is always positive" }
            implementedBy { it + 2 }
        }
        assertEquals(5, s.execute(3))
        assertEquals("answer is always positive", s.knowledgeTools().single().call())
    }
}
