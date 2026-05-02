package agents_engine.mcp

import agents_engine.core.agent
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertTrue

class AgentMcpToolUseTest {

    @Tag("live-mcp")
    @Tag("live-llm")
    @Test
    fun `agent uses redmine MCP tool to identify the user`() {
        val mcpUrl = System.getenv("MCP_REDMINE_URL")
        assumeTrue(mcpUrl != null, "MCP_REDMINE_URL not set; skipping")

        val mcp = McpClient.connect(mcpUrl!!)
        val mcpTools = mcp.toolDefs()
        val toolNames = mcpTools.map { it.name }.toTypedArray()

        val identifier = agent<String, String>("identifier") {
            prompt(
                """You answer questions by calling tools when useful.
                |Call exactly one tool with empty arguments, then reply with a one-line summary of its result.
                """.trimMargin()
            )
            model { ollama("gpt-oss:120b-cloud"); host = "localhost"; port = 11434; temperature = 0.0 }
            tools { mcpTools.forEach { +it } }
            budget { maxTurns = 5 }
            skills {
                skill<String, String>("answer", "Answer the user's question by calling a tool") {
                    tools(*toolNames)
                }
            }
        }

        val answer = identifier("Who am I in Redmine? Call redmine_whoami with no arguments.")
        println("Agent answer: $answer")
        assertTrue(
            answer.contains("admin", ignoreCase = true),
            "expected mention of admin user, got: $answer",
        )
    }
}
