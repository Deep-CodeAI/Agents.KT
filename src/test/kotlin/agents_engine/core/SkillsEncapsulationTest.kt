package agents_engine.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for #667 — `Agent.skills` is publicly read-only. Mutation requires
 * the `skills { }` DSL block; downcast-then-put fails at runtime via
 * unmodifiableMap. Twin of #659 (toolMap encapsulation).
 */
class SkillsEncapsulationTest {

    @Test
    fun `Agent skills is exposed as read-only Map (regression)`() {
        val a = agent<String, String>("ok") {
            skills { skill<String, String>("s", "stub") { implementedBy { it } } }
        }
        // Reads still work
        assertEquals("hi", a("hi"))
        assertTrue(a.skills["s"] != null)
    }

    @Test
    fun `downcast-then-put on agent skills fails at runtime`() {
        val a = agent<String, String>("ok") {
            skills { skill<String, String>("s", "stub") { implementedBy { it } } }
        }
        try {
            @Suppress("UNCHECKED_CAST")
            (a.skills as MutableMap<String, Skill<*, *>>)["bypass"] =
                Skill<String, String>("bypass", "x", String::class, String::class)
            // If the cast succeeded but the put didn't throw, the encapsulation is broken.
            // (Some Map implementations let the cast through but throw on mutation.)
            kotlin.test.fail("expected put on read-only view to fail at runtime")
        } catch (e: Throwable) {
            // ClassCastException (cast itself fails) or UnsupportedOperationException (put fails)
            assertTrue(
                e is ClassCastException || e is UnsupportedOperationException,
                "got unexpected: ${e::class.simpleName}",
            )
        }
    }

    @Test
    fun `skills() DSL block enforces uniqueness (regression)`() {
        try {
            agent<String, String>("dup") {
                skills {
                    skill<String, String>("s", "stub") { implementedBy { it } }
                    skill<String, String>("s", "stub") { implementedBy { it.uppercase() } }
                }
            }
            kotlin.test.fail("expected uniqueness error")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("\"s\""), "must name the duplicate skill: ${e.message}")
        }
    }
}
