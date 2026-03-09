package agents_engine.core

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SkillKnowledgeTest {

    @Test
    fun `skill has mandatory description`() {
        val s = skill<Int, Int>("add", "Adds one to the input") { implementedBy { it + 1 } }
        assertEquals("Adds one to the input", s.description)
    }

    @Test
    fun `skill with no knowledge entries has empty knowledge map`() {
        val s = skill<Int, Int>("add", "Adds one") { implementedBy { it + 1 } }
        assertTrue(s.knowledge.isEmpty())
    }

    @Test
    fun `skill knowledge entries are accessible by name`() {
        val s = skill<Int, Int>("add", "Adds one") {
            knowledge("style") { "Always prefer immutability" }
            knowledge("examples") { "Input: 1, Output: 2" }
            implementedBy { it + 1 }
        }
        assertEquals("Always prefer immutability", s.knowledge["style"]!!())
        assertEquals("Input: 1, Output: 2", s.knowledge["examples"]!!())
    }

    @Test
    fun `skill knowledge entries are evaluated lazily`() {
        var callCount = 0
        val s = skill<Int, Int>("add", "Adds one") {
            knowledge("dynamic") { callCount++; "value $callCount" }
            implementedBy { it + 1 }
        }
        assertEquals(0, callCount)
        s.knowledge["dynamic"]!!()
        assertEquals(1, callCount)
        s.knowledge["dynamic"]!!()
        assertEquals(2, callCount)
    }

    @Test
    fun `skill supports unlimited knowledge entries`() {
        val s = skill<String, String>("process", "Processes a string") {
            repeat(100) { i -> knowledge("key$i") { "value$i" } }
            implementedBy { it }
        }
        assertEquals(100, s.knowledge.size)
        assertEquals("value42", s.knowledge["key42"]!!())
    }

    @Test
    fun `SkillsBuilder skill factory accepts description`() {
        val builder = SkillsBuilder()
        val s = with(builder) {
            skill<Int, Int>("inc", "Increments by one") { implementedBy { it + 1 } }
        }
        assertEquals("Increments by one", s.description)
    }
}
