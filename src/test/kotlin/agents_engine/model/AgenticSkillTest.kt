package agents_engine.model

import agents_engine.core.skill
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgenticSkillTest {

    @Test
    fun `tools() marks skill as agentic`() {
        val s = skill<String, String>("s", "desc") {
            tools("write_file", "compile")
        }
        assertTrue(s.isAgentic)
        assertEquals(listOf("write_file", "compile"), s.toolNames)
    }

    @Test
    fun `implementedBy lambda is not agentic`() {
        val s = skill<String, String>("s", "desc") {
            implementedBy { it }
        }
        assertFalse(s.isAgentic)
        assertNull(s.toolNames)
    }

    @Test
    fun `agentic skill has no lambda implementation`() {
        val s = skill<String, String>("s", "desc") {
            tools("write_file")
        }
        assertNull(s.implementation)
    }

    @Test
    fun `agentic skill with no tools listed`() {
        val s = skill<String, String>("s", "desc") {
            tools()
        }
        assertTrue(s.isAgentic)
        assertEquals(emptyList(), s.toolNames)
    }
}
