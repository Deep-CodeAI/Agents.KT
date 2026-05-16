package agents_engine.mcp

import agents_engine.core.agent
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * #1794 — converted from MCP_REDMINE_URL-gated tests to a self-contained
 * loopback fixture. Each test spins up an agent with a `redmine_whoami`
 * skill via `McpServer.from(...)` and connects an `McpClient` to it.
 * No external infrastructure required.
 *
 * The skill name `redmine_whoami` is intentionally preserved so the
 * assertion shape (tool-name match) reflects what a real-world
 * MCP-discovered Redmine integration would surface.
 */
class McpClientLiveTest {

    private var server: McpServer? = null
    private var client: McpClient? = null

    @AfterEach
    fun teardown() {
        client?.close()
        server?.stop()
    }

    private fun loopbackUrl(): String {
        val whoamiAgent = agent<String, String>("redmine-loopback") {
            skills {
                skill<String, String>("redmine_whoami", "Returns the authenticated Redmine user") {
                    implementedBy { _ -> "User: admin (id=1, email=admin@local)" }
                }
            }
        }
        val s = McpServer.from(whoamiAgent) {
            port = 0
            expose("redmine_whoami")
        }.start()
        server = s
        return s.url
    }

    @Tag("live-mcp")
    @Test
    fun `connects to redmine mcp and exposes tools`() {
        val c = McpClient.connect(loopbackUrl()).also { client = it }
        val tools = c.toolDefs()

        assertTrue(tools.isNotEmpty(), "expected loopback server to expose tools")
        assertTrue(
            tools.any { it.name == "redmine_whoami" },
            "expected redmine_whoami tool, got: ${tools.map { it.name }}",
        )
    }

    @Tag("live-mcp")
    @Test
    fun `calls redmine_whoami and returns a non-empty result`() {
        val c = McpClient.connect(loopbackUrl()).also { client = it }
        val result = c.call("redmine_whoami", mapOf("input" to ""))

        assertNotNull(result)
        assertTrue(result.toString().isNotBlank(), "whoami should return non-blank content")
        assertTrue(
            result.toString().contains("admin", ignoreCase = true),
            "loopback whoami returns the canned admin user; got: $result",
        )
    }
}
