package agents_engine.mcp

import agents_engine.core.agent
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class AgentMcpDslTest {

    private val toStop = mutableListOf<() -> Unit>()

    @AfterTest fun cleanup() { toStop.forEach { runCatching { it() } } }

    private fun startHttp(block: MockMcpServerBuilder.() -> Unit): MockMcpServer =
        MockMcpServer.start(block).also { toStop.add { it.stop() } }

    private fun startTcp(block: MockMcpServerBuilder.() -> Unit): MockTcpMcpServer =
        MockTcpMcpServer.start(block).also { toStop.add { it.stop() } }

    @Test
    fun `HTTP server registered via mcp DSL exposes namespaced tools`() {
        val s = startHttp {
            tool("ping") { respond { _ -> listOf(textBlock("pong")) } }
        }

        val a = agent<String, String>("dsl-http") {
            mcp { server("svc") { url = s.url } }
            skills { skill<String, String>("noop", "stub") { implementedBy { "ok" } } }
        }
        toStop.add { a.mcpClients.forEach { runCatching { it.close() } } }

        assertTrue("svc.ping" in a.toolMap.keys, "got: ${a.toolMap.keys}")
        assertEquals("pong", a.toolMap["svc.ping"]!!.executor(emptyMap()))
    }

    @Test
    fun `TCP server registered via mcp DSL exposes namespaced tools`() {
        val s = startTcp {
            tool("ping") { respond { _ -> listOf(textBlock("pong")) } }
        }

        val a = agent<String, String>("dsl-tcp") {
            mcp { server("tcp-svc") { host = "127.0.0.1"; port = s.port } }
            skills { skill<String, String>("noop", "stub") { implementedBy { "ok" } } }
        }
        toStop.add { a.mcpClients.forEach { runCatching { it.close() } } }

        assertTrue("tcp-svc.ping" in a.toolMap.keys, "got: ${a.toolMap.keys}")
        assertEquals("pong", a.toolMap["tcp-svc.ping"]!!.executor(emptyMap()))
    }

    @Test
    fun `two servers in one block with same tool name do not collide`() {
        val a = startHttp { tool("read") { respond { _ -> listOf(textBlock("from-a")) } } }
        val b = startHttp { tool("read") { respond { _ -> listOf(textBlock("from-b")) } } }

        val ag = agent<String, String>("dsl-collision") {
            mcp {
                server("alpha") { url = a.url }
                server("beta") { url = b.url }
            }
            skills { skill<String, String>("noop", "stub") { implementedBy { "ok" } } }
        }
        toStop.add { ag.mcpClients.forEach { runCatching { it.close() } } }

        assertEquals("from-a", ag.toolMap["alpha.read"]!!.executor(emptyMap()))
        assertEquals("from-b", ag.toolMap["beta.read"]!!.executor(emptyMap()))
    }

    @Test
    fun `Bearer auth threads through HTTP server config`() {
        val s = startHttp {
            requireBearer("token-xyz")
            tool("ping") { respond { _ -> listOf(textBlock("authorized")) } }
        }

        val a = agent<String, String>("dsl-auth") {
            mcp {
                server("secured") {
                    url = s.url
                    auth = McpAuth.Bearer("token-xyz")
                }
            }
            skills { skill<String, String>("noop", "stub") { implementedBy { "ok" } } }
        }
        toStop.add { a.mcpClients.forEach { runCatching { it.close() } } }

        assertEquals("authorized", a.toolMap["secured.ping"]!!.executor(emptyMap()))
    }

    @Test
    fun `validation - zero transports throws`() {
        try {
            agent<String, String>("bad") {
                mcp { server("nothing") { /* no url, no command, no host+port */ } }
                skills { skill<String, String>("noop", "stub") { implementedBy { "ok" } } }
            }
            fail("expected validation error")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("exactly one transport"), "got: ${e.message}")
        }
    }

    @Test
    fun `validation - multiple transports throws`() {
        try {
            agent<String, String>("bad") {
                mcp {
                    server("conflict") {
                        url = "http://localhost:9999/mcp"
                        host = "127.0.0.1"
                        port = 9999
                    }
                }
                skills { skill<String, String>("noop", "stub") { implementedBy { "ok" } } }
            }
            fail("expected validation error")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("exactly one transport"), "got: ${e.message}")
        }
    }

    @Test
    fun `validation - auth on non-HTTP transport throws`() {
        try {
            agent<String, String>("bad") {
                mcp {
                    server("tcp-with-auth") {
                        host = "127.0.0.1"; port = 9999
                        auth = McpAuth.Bearer("oops")
                    }
                }
                skills { skill<String, String>("noop", "stub") { implementedBy { "ok" } } }
            }
            fail("expected validation error")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("auth"), "got: ${e.message}")
        }
    }

    @Test
    fun `validation - duplicate server name throws`() {
        try {
            agent<String, String>("bad") {
                mcp {
                    server("svc") { url = "http://localhost:9999/mcp" }
                    server("svc") { url = "http://localhost:9998/mcp" }
                }
                skills { skill<String, String>("noop", "stub") { implementedBy { "ok" } } }
            }
            fail("expected validation error")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Duplicate"), "got: ${e.message}")
        }
    }

    @Test
    fun `agent mcpClients exposes connected clients for cleanup`() {
        val s = startHttp { tool("ping") { } }

        val a = agent<String, String>("dsl-cleanup") {
            mcp { server("svc") { url = s.url } }
            skills { skill<String, String>("noop", "stub") { implementedBy { "ok" } } }
        }
        assertEquals(1, a.mcpClients.size)
        a.mcpClients.forEach { it.close() }
    }
}
