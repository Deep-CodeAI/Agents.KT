package agents_engine.mcp

import agents_engine.core.agent
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #1795 — first slice of the MCP-as-skills unification. An MCP tool and
 * an agent Skill are conceptually the same shape: named, described,
 * typed unit of work. `mcp.toolSkills()` exposes every server-side tool
 * as a `Skill<Map<String, Any?>, String>` ready to drop into an agent's
 * `skills { +... }` block.
 *
 * This test stands up a loopback algebra agent (computes sqrt(π/e)),
 * exposes it via McpServer, connects an McpClient, asks for the
 * tool-skills, and uses the returned skill as a primary skill on a
 * caller agent. The caller's `Agent<Map<String, Any?>, String>` input
 * is the MCP tool's expected args map.
 */
class McpToolsAsSkillsTest {

    private var mcpServer: McpServer? = null
    private var mcpClient: McpClient? = null

    @AfterEach
    fun teardown() {
        mcpClient?.close()
        mcpServer?.stop()
    }

    @Tag("live-mcp")
    @Test
    fun `mcp toolSkills returns each MCP tool as a Skill usable as an agent primary skill`() {
        // Server side: an agent whose skill computes sqrt(π/e).
        val algebra = agent<String, String>("algebra") {
            skills {
                skill<String, String>("compute_sqrt_pi_over_e", "Computes sqrt(pi/e) to 30 decimal digits") {
                    implementedBy { _ ->
                        // Trim the loopback algebra test's 50-digit output to 30 for this test.
                        // (Inline literal is fine for this scope — the real math is exercised
                        //  in LoopbackMcpAlgebraTest; here we're testing the skills wrapping.)
                        "1.07504760349992023872275586024820"
                    }
                }
            }
        }
        val server = McpServer.from(algebra) {
            port = 0
            expose("compute_sqrt_pi_over_e")
        }.start().also { mcpServer = it }

        val mcp = McpClient.connect(server.url).also { mcpClient = it }

        // Discovery: every MCP tool returned as a Skill.
        val skills = mcp.toolSkills()
        assertEquals(1, skills.size, "expected one tool-skill; got: ${skills.map { it.name }}")
        val toolSkill = skills.single()
        assertEquals("compute_sqrt_pi_over_e", toolSkill.name)
        assertTrue(
            toolSkill.description.contains("sqrt", ignoreCase = true),
            "expected MCP tool description to carry through; got: ${toolSkill.description}",
        )

        // Use the MCP-derived skill as a primary skill on a caller agent.
        val caller = agent<Map<String, Any?>, String>("caller") {
            skills { +toolSkill }
        }

        // Invoke caller — input is the MCP tool's args map (`{input: ""}` for
        // String-input MCP-exposed skills).
        val output = caller(mapOf("input" to "go"))
        assertTrue(
            output.startsWith("1.0750476"),
            "expected sqrt(π/e) digits round-tripped via MCP; got: \"$output\"",
        )
    }

    @Tag("live-mcp")
    @Test
    fun `mcp tools returns typed tool handles equivalent to toolSkills`() = runBlocking {
        val algebra = agent<String, String>("algebra") {
            skills {
                skill<String, String>("compute_sqrt_pi_over_e", "Computes sqrt(pi/e) to 30 decimal digits") {
                    implementedBy { _ -> "1.07504760349992023872275586024820" }
                }
            }
        }
        val server = McpServer.from(algebra) {
            port = 0
            expose("compute_sqrt_pi_over_e")
        }.start().also { mcpServer = it }

        val mcp = McpClient.connect(server.url).also { mcpClient = it }

        val skillShape = mcp.toolSkills().single()
        val toolShape = mcp.tools().single()

        assertEquals(skillShape.name, toolShape.name)
        assertEquals(skillShape.description, toolShape.description)
        assertEquals(Map::class, toolShape.inputType)
        assertEquals(String::class, toolShape.outputType)

        val output = toolShape.call(mapOf("input" to "go"))

        assertEquals(skillShape(mapOf("input" to "go")), output)
        assertTrue(output.startsWith("1.0750476"))
    }
}
