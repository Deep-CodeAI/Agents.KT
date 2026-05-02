package agents_engine.mcp

import agents_engine.core.agent
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ToolNameNamespacingTest {

    private val toClose = mutableListOf<AutoCloseable>()
    private val toStop = mutableListOf<() -> Unit>()

    @AfterTest fun cleanup() {
        toClose.forEach { runCatching { it.close() } }
        toStop.forEach { runCatching { it() } }
    }

    @Test
    fun `toolDefs with prefix produces server dot tool names`() {
        val s = MockMcpServer.start {
            tool("read_file") { description = "reads" }
            tool("write_file") { description = "writes" }
        }.also { toStop.add { it.stop() } }

        val client = McpClient.connect(s.url).also { toClose.add(it) }

        val tools = client.toolDefs(prefix = "fs")
        assertEquals(setOf("fs.read_file", "fs.write_file"), tools.map { it.name }.toSet())
    }

    @Test
    fun `prefixed tool executor calls the unprefixed wire name`() {
        val s = MockMcpServer.start {
            tool("ping") { respond { _ -> listOf(textBlock("pong")) } }
        }.also { toStop.add { it.stop() } }

        val client = McpClient.connect(s.url).also { toClose.add(it) }
        val tool = client.toolDefs(prefix = "redmine").single()

        assertEquals("redmine.ping", tool.name)
        assertEquals("pong", tool.executor(emptyMap()))
    }

    @Test
    fun `two clients with different prefixes register into one agent without collision`() {
        val a = MockMcpServer.start {
            tool("read_file") { respond { _ -> listOf(textBlock("from-fs")) } }
        }.also { toStop.add { it.stop() } }
        val b = MockMcpServer.start {
            tool("read_file") { respond { _ -> listOf(textBlock("from-github")) } }
        }.also { toStop.add { it.stop() } }

        val ca = McpClient.connect(a.url).also { toClose.add(it) }
        val cb = McpClient.connect(b.url).also { toClose.add(it) }

        val fsTools = ca.toolDefs(prefix = "fs")
        val ghTools = cb.toolDefs(prefix = "github")

        // Would throw on duplicate "read_file" name without prefixes — verifies registration succeeds.
        val pinned = agent<String, String>("multi-mcp") {
            tools {
                fsTools.forEach { +it }
                ghTools.forEach { +it }
            }
            skills {
                skill<String, String>("noop", "stub") {
                    implementedBy { "ok" }
                }
            }
        }

        val toolNames = pinned.toolMap.keys
        assertTrue("fs.read_file" in toolNames, "got: $toolNames")
        assertTrue("github.read_file" in toolNames, "got: $toolNames")

        // Each prefixed tool routes to its own server.
        assertEquals("from-fs", pinned.toolMap["fs.read_file"]!!.executor(emptyMap()))
        assertEquals("from-github", pinned.toolMap["github.read_file"]!!.executor(emptyMap()))
    }

    @Test
    fun `default toolDefs has no prefix - backward compat`() {
        val s = MockMcpServer.start {
            tool("ping") { }
        }.also { toStop.add { it.stop() } }

        val client = McpClient.connect(s.url).also { toClose.add(it) }
        assertEquals("ping", client.toolDefs().single().name)
    }
}
