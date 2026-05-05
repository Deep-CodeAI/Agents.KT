@file:Suppress("DEPRECATION")

package agents_engine.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Tests for #668 — Skill mutators throw after agent construction so the agent's
 * tool-allowlist composition and agentic/deterministic dispatch can't drift
 * post-construction via a held Skill reference.
 */
class SkillFreezeTest {

    @Suppress("UNCHECKED_CAST")
    private fun trivialAgent(): Pair<Skill<String, String>, Agent<String, String>> {
        val a = agent<String, String>("x") {
            skills { skill<String, String>("s", "stub") { implementedBy { it } } }
        }
        return a.skills["s"] as Skill<String, String> to a
    }

    @Test
    fun `knowledge mutator throws after construction`() {
        val (s, _) = trivialAgent()
        try { s.knowledge("late") { "x" }; fail("expected freeze rejection") }
        catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("s"), "must name the skill: ${e.message}")
            assertTrue(e.message!!.contains("frozen", ignoreCase = true), "must explain why: ${e.message}")
        }
    }

    @Test
    fun `implementedBy mutator throws after construction`() {
        val (s, _) = trivialAgent()
        try { s.implementedBy { "different" }; fail("expected freeze rejection") }
        catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("frozen", ignoreCase = true))
        }
    }

    @Test
    fun `tools mutator throws after construction (would convert deterministic skill to agentic)`() {
        val (s, _) = trivialAgent()
        try { s.tools("ghost"); fail("expected freeze rejection") }
        catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("frozen", ignoreCase = true))
        }
    }

    @Test
    fun `llmDescription mutator throws after construction`() {
        val (s, _) = trivialAgent()
        try { s.llmDescription("late override"); fail("expected freeze rejection") }
        catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("frozen", ignoreCase = true))
        }
    }

    @Test
    fun `transformOutput mutator throws after construction`() {
        val (s, _) = trivialAgent()
        try { s.transformOutput { it }; fail("expected freeze rejection") }
        catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("frozen", ignoreCase = true))
        }
    }

    @Test
    fun `Skill implementation setter is private (#698)`() {
        val (s, _) = trivialAgent()
        // The setter must be private — the only legitimate path to mutate
        // is implementedBy(), which is freeze-checked.
        val prop = Skill::class.members.first { it.name == "implementation" } as kotlin.reflect.KMutableProperty<*>
        assertTrue(
            prop.setter.visibility == kotlin.reflect.KVisibility.PRIVATE,
            "Skill.implementation setter must be private; got ${prop.setter.visibility}",
        )
        // Sanity: implementedBy is still the legitimate path AND is now freeze-checked
        try { s.implementedBy { "via legit path" }; fail("expected freeze rejection") }
        catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("frozen", ignoreCase = true))
        }
    }

    @Test
    fun `mutation inside skills block still works (regression)`() {
        val a = agent<String, String>("ok") {
            skills {
                skill<String, String>("s", "stub") {
                    knowledge("inside") { "rules" }
                    implementedBy { "result" }
                }
            }
        }
        assertEquals("result", a("input"))
    }
}
