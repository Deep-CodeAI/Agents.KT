package agents_engine.agntcy

import agents_engine.core.agent
import agents_engine.core.skill
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// #4519 (PRD §12.6) — OASF record import + validate, the read side of toOasfRecord (#4518).
// Hermetic. The headline guarantee is round-trip symmetry (export -> import) plus fail-closed
// validation: a record that lies about a skill's taxonomy id, or uses an unknown schema major, is
// rejected rather than silently accepted.
class OasfImportTest {

    private fun catalogAgent() = agent<String, String>("catalog") {
        skills {
            skill<String, String>("plan", "") {
                oasf("agent_orchestration/multi_agent_planning") // -> 1003
                implementedBy { "ok" }
            }
        }
    }

    @Test
    fun `export then import round-trips`() {
        val json = catalogAgent().toOasfRecord(
            version = "1.2.0",
            authors = listOf("Ada <ada@example.com>"),
            createdAt = "2026-06-16T00:00:00Z",
        )
        val record = fromOasfRecord(json)
        assertEquals("catalog", record.name)
        assertEquals("1.2.0", record.version)
        assertEquals("1.0.0", record.schemaVersion)
        assertEquals("2026-06-16T00:00:00Z", record.createdAt)
        val skill = record.skills.single()
        assertEquals("agent_orchestration/multi_agent_planning", skill.name)
        assertEquals(1003, skill.id)
    }

    @Test
    fun `import resolves name to id and id to name from the taxonomy`() {
        val byName = fromOasfRecord(
            """{"name":"a","schema_version":"1.0.0","skills":[{"name":"agent_orchestration/multi_agent_planning"}]}""",
        )
        assertEquals(1003, byName.skills.single().id)

        val byId = fromOasfRecord(
            """{"name":"a","schema_version":"1.0.0","skills":[{"id":1003}]}""",
        )
        assertEquals("agent_orchestration/multi_agent_planning", byId.skills.single().name)
    }

    @Test
    fun `a missing name is rejected`() {
        assertFailsWith<OasfValidationException> {
            fromOasfRecord("""{"schema_version":"1.0.0","skills":[]}""")
        }
    }

    @Test
    fun `an unknown schema major is rejected`() {
        val e = assertFailsWith<OasfValidationException> {
            fromOasfRecord("""{"name":"a","schema_version":"2.0.0","skills":[]}""")
        }
        assertTrue("schema_version" in e.message.orEmpty(), e.message.orEmpty())
    }

    @Test
    fun `a skill with neither id nor name is rejected (at_least_one)`() {
        assertFailsWith<OasfValidationException> {
            fromOasfRecord("""{"name":"a","schema_version":"1.0.0","skills":[{}]}""")
        }
    }

    @Test
    fun `a skill whose id contradicts its name is rejected`() {
        val skill = """{"name":"agent_orchestration/multi_agent_planning","id":9999}"""
        val e = assertFailsWith<OasfValidationException> {
            fromOasfRecord("""{"name":"a","schema_version":"1.0.0","skills":[$skill]}""")
        }
        assertTrue("9999" in e.message.orEmpty() || "multi_agent_planning" in e.message.orEmpty(), e.message.orEmpty())
    }
}
