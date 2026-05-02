package agents_engine.mcp

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class McpClientLiveTest {

    @Tag("live-mcp")
    @Test
    fun `connects to redmine mcp and exposes tools`() {
        val url = System.getenv("MCP_REDMINE_URL")
        assumeTrue(url != null, "MCP_REDMINE_URL not set; skipping")

        val client = McpClient.connect(url!!)
        val tools = client.toolDefs()

        assertTrue(tools.isNotEmpty(), "expected redmine to expose tools")
        assertTrue(
            tools.any { it.name == "redmine_whoami" },
            "expected redmine_whoami tool, got: ${tools.map { it.name }}",
        )
    }

    @Tag("live-mcp")
    @Test
    fun `calls redmine_whoami and returns a non-empty result`() {
        val url = System.getenv("MCP_REDMINE_URL")
        assumeTrue(url != null, "MCP_REDMINE_URL not set; skipping")

        val client = McpClient.connect(url!!)
        val result = client.call("redmine_whoami", emptyMap())

        assertNotNull(result)
        assertTrue(result.toString().isNotBlank(), "whoami should return non-blank content")
        println("redmine_whoami → $result")
    }
}
