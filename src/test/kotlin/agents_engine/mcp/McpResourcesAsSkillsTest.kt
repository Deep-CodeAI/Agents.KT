package agents_engine.mcp

import agents_engine.core.agent
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #1810 — slice 3/3 of MCP-as-skills. MCP resources are URI-addressable
 * data items. `mcp.resourceSkills()` exposes each as a Skill whose
 * `implementedBy` reads the URI's content. Skill args are ignored —
 * the URI is captured in the skill's closure at fetch time.
 */
class McpResourcesAsSkillsTest {

    private var mcpServer: McpServer? = null
    private var mcpClient: McpClient? = null

    @AfterEach
    fun teardown() {
        mcpClient?.close()
        mcpServer?.stop()
    }

    @Tag("live-mcp")
    @Test
    fun `mcp resourceSkills returns each MCP resource as a Skill that reads its URI content`() {
        val placeholderAgent = agent<String, String>("placeholder") {
            skills {
                skill<String, String>("noop", "Placeholder skill") {
                    implementedBy { it }
                }
            }
        }

        val server = McpServer.from(placeholderAgent) {
            port = 0
            expose("noop")
            resource(
                uri = "policy:///precision-policy.md",
                name = "precision-policy",
                description = "Internal policy for math problem precision",
                mimeType = "text/markdown",
            ) {
                "Be precise. Cite sources. Round half-to-even."
            }
        }.start().also { mcpServer = it }

        val mcp = McpClient.connect(server.url).also { mcpClient = it }

        // Discovery: every MCP resource returned as a Skill.
        val resourceSkills = mcp.resourceSkills()
        assertEquals(1, resourceSkills.size, "expected one resource-skill; got: ${resourceSkills.map { it.name }}")
        val skill = resourceSkills.single()
        assertEquals("precision-policy", skill.name)
        assertTrue(
            skill.description.contains("precision", ignoreCase = true),
            "expected MCP resource description; got: ${skill.description}",
        )

        // Use the MCP-resource-as-skill as a primary skill on a caller agent.
        val caller = agent<Map<String, Any?>, String>("caller") {
            skills { +skill }
        }

        // Args are ignored — the resource URI is captured by the skill's closure.
        val output = caller(emptyMap())
        assertTrue(
            "precise" in output && "sources" in output,
            "expected resource content; got: \"$output\"",
        )
    }
}
