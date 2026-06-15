package agents_engine.agntcy

import agents_engine.core.agent
import agents_engine.core.skill
import agents_engine.core.toAgentJson
import agents_engine.generation.LenientJsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// #4518 (PRD §12.6) — OASF 1.0.0 record export, the third discovery exporter beside
// A2A toAgentCard() (§12.5) and native agent.json (§12.2). Skills carry OASF taxonomy IDs
// via the opt-in `.oasf("path")` annotation; the vendored taxonomy maps path -> uid.
// Hermetic — pure serialization, no network. Determinism is part of the contract.
class OasfRecordTest {

    private fun catalogAgent() = agent<String, String>("catalog") {
        skills {
            skill<String, String>("plan", "Decomposes a goal into agent tasks") {
                oasf("agent_orchestration/multi_agent_planning") // -> 1003
                implementedBy { "ok" }
            }
            skill<String, String>("retrieve", "Finds similar sentences") {
                oasf("natural_language_processing/information_retrieval_synthesis/sentence_similarity") // -> 10304
                implementedBy { "ok" }
            }
        }
    }

    @Test
    fun `vendored taxonomy maps known OASF paths to their uids`() {
        assertEquals(1003, OasfTaxonomy.skillUid("agent_orchestration/multi_agent_planning"))
        assertEquals(
            10304,
            OasfTaxonomy.skillUid("natural_language_processing/information_retrieval_synthesis/sentence_similarity"),
        )
        assertEquals(50202, OasfTaxonomy.skillUid("analytical_skills/coding_skills/code_to_docstrings"))
        assertEquals(1001, OasfTaxonomy.skillUid("agent_orchestration/task_decomposition"))
        assertEquals(10702, OasfTaxonomy.skillUid("natural_language_processing/analytical_reasoning/problem_solving"))
        assertNull(OasfTaxonomy.skillUid("not/a/real/skill"))
        // slice 2 — domains tree is now vendored too
        assertEquals(1003, OasfTaxonomy.domainUid("legal/regulatory_compliance"))
        assertNull(OasfTaxonomy.domainUid("not/a/real/domain"))
    }

    @Test
    fun `toOasfRecord emits taxonomy ids for annotated skills`() {
        val json = catalogAgent().toOasfRecord(
            version = "1.2.0",
            authors = listOf("Ada Lovelace <ada@example.com>"),
            locators = listOf(OasfLocator(type = "source_code", urls = listOf("https://example.com/catalog"))),
            domains = listOf("legal/regulatory_compliance"),
            description = "A catalog agent",
            createdAt = "2026-06-15T00:00:00Z",
        )
        @Suppress("UNCHECKED_CAST")
        val root = LenientJsonParser.parse(json) as Map<String, Any?>
        assertEquals("catalog", root["name"])
        assertEquals("1.2.0", root["version"])
        assertEquals("1.0.0", root["schema_version"])
        assertEquals("2026-06-15T00:00:00Z", root["created_at"])

        @Suppress("UNCHECKED_CAST")
        val skills = root["skills"] as List<Map<String, Any?>>
        // sorted by skill name -> "plan" before "retrieve"
        val byPath = skills.associate { it["name"] to (it["id"] as Number).toInt() }
        assertEquals(1003, byPath["agent_orchestration/multi_agent_planning"])
        assertEquals(
            10304,
            byPath["natural_language_processing/information_retrieval_synthesis/sentence_similarity"],
        )

        @Suppress("UNCHECKED_CAST")
        val locators = root["locators"] as List<Map<String, Any?>>
        assertEquals("source_code", locators.single()["type"])

        @Suppress("UNCHECKED_CAST")
        val domains = root["domains"] as List<Map<String, Any?>>
        assertEquals("legal/regulatory_compliance", domains.single()["name"])
        assertEquals(1003, (domains.single()["id"] as Number).toInt())
    }

    @Test
    fun `un-annotated skills are omitted from the OASF skills array`() {
        val a = agent<String, String>("partial") {
            skills {
                skill<String, String>("tagged", "") {
                    oasf("agent_orchestration/multi_agent_planning")
                    implementedBy { "x" }
                }
                skill<String, String>("untagged", "") { implementedBy { "x" } }
            }
        }
        @Suppress("UNCHECKED_CAST")
        val root = LenientJsonParser.parse(a.toOasfRecord(version = "1.0.0")) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val skills = root["skills"] as List<Map<String, Any?>>
        assertEquals(1, skills.size, "only the .oasf-annotated skill is a taxonomy entry")
        assertEquals("agent_orchestration/multi_agent_planning", skills.single()["name"])
    }

    @Test
    fun `record is byte-stable for the same inputs`() {
        val a = catalogAgent()
        val first = a.toOasfRecord(version = "1.2.0", createdAt = "2026-06-15T00:00:00Z")
        val second = a.toOasfRecord(version = "1.2.0", createdAt = "2026-06-15T00:00:00Z")
        assertEquals(first, second)
    }

    @Test
    fun `agent_json carries additive provenance fields when supplied`() {
        val json = catalogAgent().toAgentJson(
            version = "1.2.0",
            authors = listOf("Ada Lovelace <ada@example.com>"),
            createdAt = "2026-06-15T00:00:00Z",
            locators = listOf(OasfLocator(type = "source_code", urls = listOf("https://example.com/catalog"))),
        )
        @Suppress("UNCHECKED_CAST")
        val root = LenientJsonParser.parse(json) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val metadata = root["metadata"] as Map<String, Any?>
        assertEquals("2026-06-15T00:00:00Z", metadata["createdAt"])
        @Suppress("UNCHECKED_CAST")
        val authors = metadata["authors"] as List<Any?>
        assertTrue("Ada Lovelace <ada@example.com>" in authors)
        @Suppress("UNCHECKED_CAST")
        val spec = root["spec"] as Map<String, Any?>
        assertTrue(spec.containsKey("locators"))
    }
}
