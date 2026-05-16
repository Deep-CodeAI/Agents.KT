package agents_engine.mcp

import agents_engine.core.agent
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertTrue
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * #1794 — converted to a loopback MCP fixture. The MCP wire is in-JVM;
 * Ollama is still required for the agentic side, so this stays `live-llm`
 * tagged. When Ollama isn't reachable the test skips cleanly.
 *
 * Demonstrates: an agent discovers tools from a (loopback) MCP server,
 * routes a user question through Ollama, and the LLM invokes the
 * discovered tool. End-to-end MCP-as-tools integration.
 */
class AgentMcpToolUseTest {

    private var mcpServer: McpServer? = null
    private var mcpClient: McpClient? = null

    @AfterEach
    fun teardown() {
        mcpClient?.close()
        mcpServer?.stop()
    }

    @Tag("live-mcp")
    @Tag("live-llm")
    @Test
    fun `agent uses redmine MCP tool to identify the user`() {
        assumeTrue(isOllamaReachable(), "skipping: no Ollama at localhost:11434")

        // Loopback MCP server with the canonical Redmine-style identity tool.
        val whoamiAgent = agent<String, String>("redmine-loopback") {
            skills {
                skill<String, String>("redmine_whoami", "Returns the authenticated Redmine user") {
                    implementedBy { _ -> "User: admin (id=1, email=admin@local)" }
                }
            }
        }
        val server = McpServer.from(whoamiAgent) {
            port = 0
            expose("redmine_whoami")
        }.start().also { mcpServer = it }

        val mcp = McpClient.connect(server.url).also { mcpClient = it }
        val mcpTools = mcp.toolDefs()
        val toolNames = mcpTools.map { it.name }.toTypedArray()

        val identifier = agent<String, String>("identifier") {
            prompt(
                """You answer questions by calling tools when useful.
                |Call exactly one tool with arguments `{"input": ""}` (the tool's schema requires `input`),
                |then reply with a one-line summary of its result.
                """.trimMargin()
            )
            model { ollama("gpt-oss:120b-cloud"); host = "localhost"; port = 11434; temperature = 0.0 }
            tools { mcpTools.forEach { +it } }
            budget { maxTurns = 5 }
            skills {
                skill<String, String>("answer", "Answer the user's question by calling a tool") {
                    @Suppress("DEPRECATION") // MCP tools discovered at runtime — names aren't compile-time refs
                    tools(*toolNames)
                }
            }
        }

        val answer = identifier("Who am I in Redmine? Call redmine_whoami with no arguments.")
        println("AgentMcpToolUseTest: agent answer: $answer")
        assertTrue(
            answer.contains("admin", ignoreCase = true),
            "expected mention of admin user, got: $answer",
        )
    }

    private fun isOllamaReachable(): Boolean = try {
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(500)).build()
        val request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:11434/api/tags"))
            .timeout(Duration.ofMillis(1500))
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.discarding())
        response.statusCode() in 200..299
    } catch (_: Throwable) {
        false
    }
}
