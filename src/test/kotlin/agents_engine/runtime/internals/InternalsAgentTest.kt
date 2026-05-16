package agents_engine.runtime.internals

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InternalsAgentTest {

    @Test
    fun `registers one skill per internals-agent adjunct`() {
        val agent = buildInternalsAgent()
        // 57 in src/main/kotlin + 6 in agents-kt-ksp = 63 adjuncts at v0.6.0.
        // If a new adjunct lands, this bumps with it — that's the contract.
        assertEquals(63, agent.skills.size, "Skill count drifted from adjunct file count.")
    }

    @Test
    fun `every skill has a non-blank description`() {
        val agent = buildInternalsAgent()
        val blanks = agent.skills.values.filter { it.description.isBlank() }
        assertTrue(blanks.isEmpty(), "Skills with blank descriptions: ${blanks.map { it.name }}")
    }

    @Test
    fun `well-known skills are present`() {
        val names = buildInternalsAgent().skills.keys
        listOf(
            "core_agent_kt",
            "core_skill_kt",
            "model_agenticloop_kt",
            "composition_pipeline_pipeline_kt",
            "mcp_mcpclient_kt",
            "runtime_events_agentevent_kt",
            "ksp_agentsktsymbolprocessor_kt",
        ).forEach { expected ->
            assertTrue(expected in names, "Missing expected skill `$expected`. Found: ${names.sorted()}")
        }
    }

    @Test
    fun `skill invocation returns the adjunct body`() {
        val agent = buildInternalsAgent()
        val skill = agent.skills["core_agent_kt"] ?: error("core_agent_kt skill missing")
        @Suppress("UNCHECKED_CAST")
        val out = (skill as agents_engine.core.Skill<String, String>).invoke("")
        assertTrue(out.contains("Agent"), "Expected Agent.md body, got: ${out.take(120)}")
        assertTrue(out.contains("# `agents_engine/core/Agent.kt`"), "Expected H1 from Agent.md")
    }
}
