package agents_engine.runtime.internals

import java.io.File
import java.net.JarURLConnection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InternalsAgentTest {

    @Test
    fun `registers exactly one skill per internals-agent adjunct on the classpath`() {
        val agent = buildInternalsAgent()
        val adjunctCount = countAdjunctsOnClasspath()
        // Contract: skill count ALWAYS equals the .md file count under
        // src/main/resources/internals-agent/. Adding a new adjunct should
        // make this test pass automatically (and bump the skill count); a
        // drift means the scanner missed a file or registered a duplicate.
        assertEquals(
            adjunctCount,
            agent.skills.size,
            "Skill count must equal adjunct count. " +
                "Adjuncts on classpath: $adjunctCount. Registered skills: ${agent.skills.size}. " +
                "Skill names: ${agent.skills.keys.sorted()}",
        )
        // Sanity: the scanner found SOME adjuncts. Catches the regression
        // where the resources dir gets wiped or relocated.
        assertTrue(adjunctCount > 0, "Found 0 adjuncts on classpath — the scanner is broken or the resources dir moved.")
    }

    /**
     * Walks the classpath the same way `InternalsAgent.kt` does, but kept
     * test-local so this test fails loudly when the production scanner
     * silently drifts away from the resource layout.
     */
    private fun countAdjunctsOnClasspath(): Int {
        val cl = Thread.currentThread().contextClassLoader
            ?: ::countAdjunctsOnClasspath.javaClass.classLoader
        val url = cl.getResource("internals-agent") ?: return 0
        return when (url.protocol) {
            "file" -> {
                val root = File(url.toURI())
                root.walkTopDown().count { it.isFile && it.extension == "md" }
            }
            "jar" -> {
                val conn = url.openConnection() as JarURLConnection
                conn.jarFile.entries().asSequence().count {
                    !it.isDirectory && it.name.startsWith("internals-agent/") && it.name.endsWith(".md")
                }
            }
            else -> error("Unsupported classpath resource protocol: ${url.protocol}")
        }
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
