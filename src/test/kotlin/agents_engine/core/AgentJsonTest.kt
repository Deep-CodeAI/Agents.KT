package agents_engine.core

import agents_engine.generation.LenientJsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// #4516 (PRD §12.2) — agent.json serialization. TDD: a built agent serializes to the documented
// shape (apiVersion/kind/metadata/spec.types/skills/tools/capabilities), parseable + deterministic.

class AgentJsonTest {

    private fun sample() = agent<String, Int>("counter") {
        tools { tool("count", "Counts things") { _ -> 0 } }
        skills { skill<String, Int>("len", "Length of the input") { implementedBy { it.length } } }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parse(json: String) = LenientJsonParser.parse(json) as Map<String, Any?>

    @Test
    fun `serializes the documented agent_json shape`() {
        val doc = parse(sample().toAgentJson(version = "2.1.0", description = "Counts characters"))

        assertEquals("Agent", doc["kind"])
        assertTrue((doc["apiVersion"] as String).startsWith("agents-kt"))

        val meta = doc["metadata"] as Map<*, *>
        assertEquals("counter", meta["name"])
        assertEquals("2.1.0", meta["version"])
        assertEquals("Counts characters", meta["description"])

        val spec = doc["spec"] as Map<*, *>
        val types = spec["types"] as Map<*, *>
        val consumes = types["consumes"] as List<*>
        assertTrue(consumes.any { (it as String).contains("String") }, "consumes: $consumes")
        assertTrue((types["produces"] as String).contains("Int"), "produces: ${types["produces"]}")

        val skills = spec["skills"] as List<*>
        val len = skills.map { it as Map<*, *> }.single { it["name"] == "len" }
        assertEquals("Length of the input", len["description"])

        val tools = spec["tools"] as List<*>
        assertTrue(tools.map { it as Map<*, *> }.any { it["name"] == "count" }, "tools: $tools")

        assertEquals(true, (spec["capabilities"] as Map<*, *>)["streaming"])
    }

    @Test
    fun `output is deterministic`() {
        val a = sample()
        assertEquals(a.toAgentJson(version = "1.0.0"), a.toAgentJson(version = "1.0.0"))
    }

    @Test
    fun `metadata omits null version and description`() {
        val meta = parse(sample().toAgentJson()) ["metadata"] as Map<*, *>
        assertEquals("counter", meta["name"])
        assertTrue("version" !in meta, "no null version key: $meta")
        assertTrue("description" !in meta, "no null description key: $meta")
    }
}
