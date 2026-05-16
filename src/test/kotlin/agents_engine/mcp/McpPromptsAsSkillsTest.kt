package agents_engine.mcp

import agents_engine.core.agent
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #1796 — slice 2/3 of MCP-as-skills. MCP prompts are server-side
 * named, argument-templated text producers. `mcp.promptSkills()`
 * exposes them as `Skill<Map<String, Any?>, String>` — same shape as
 * `toolSkills()` from slice 1.
 */
class McpPromptsAsSkillsTest {

    private var mcpServer: McpServer? = null
    private var mcpClient: McpClient? = null

    @AfterEach
    fun teardown() {
        mcpClient?.close()
        mcpServer?.stop()
    }

    @Tag("live-mcp")
    @Test
    fun `mcp promptSkills returns each MCP prompt as a Skill that renders the template`() {
        // Any agent works — the prompt registration is McpServer-level, not skill-level.
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
            prompt(
                name = "review_math",
                description = "System prompt for math reviewers",
                arguments = listOf(McpPromptArgument(name = "topic", description = "Math topic", required = true)),
            ) { args ->
                "You are reviewing math problems on ${args["topic"]}. Be precise."
            }
        }.start().also { mcpServer = it }

        val mcp = McpClient.connect(server.url).also { mcpClient = it }

        // Discovery: every MCP prompt returned as a Skill.
        val promptSkills = mcp.promptSkills()
        assertEquals(1, promptSkills.size, "expected one prompt-skill; got: ${promptSkills.map { it.name }}")
        val skill = promptSkills.single()
        assertEquals("review_math", skill.name)
        assertTrue(
            skill.description.contains("math reviewers", ignoreCase = true),
            "expected MCP prompt description; got: ${skill.description}",
        )

        // Use the MCP-prompt-as-skill as a primary skill on a caller agent.
        val caller = agent<Map<String, Any?>, String>("caller") {
            skills { +skill }
        }

        val output = caller(mapOf("topic" to "trigonometry"))
        assertTrue(
            "trigonometry" in output && "math problems" in output,
            "expected rendered template to interpolate args; got: \"$output\"",
        )
    }
}
