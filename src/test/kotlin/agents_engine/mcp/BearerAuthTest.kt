package agents_engine.mcp

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class BearerAuthTest {

    private val toClose = mutableListOf<AutoCloseable>()
    private val toStop = mutableListOf<() -> Unit>()

    @AfterTest fun cleanup() {
        toClose.forEach { runCatching { it.close() } }
        toStop.forEach { runCatching { it() } }
    }

    @Test
    fun `correct Bearer token unlocks the server`() {
        val s = MockMcpServer.start {
            requireBearer("secret-token")
            tool("ping") { respond { _ -> listOf(textBlock("pong")) } }
        }.also { toStop.add { it.stop() } }

        val client = McpClient.connect(s.url, McpAuth.Bearer("secret-token")).also { toClose.add(it) }
        assertEquals("pong", client.call("ping", emptyMap()))
    }

    @Test
    fun `missing Authorization header is rejected with 401`() {
        val s = MockMcpServer.start {
            requireBearer("secret-token")
            tool("ping") { }
        }.also { toStop.add { it.stop() } }

        try {
            McpClient.connect(s.url)  // McpAuth.None by default
            fail("expected 401 on initialize")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("401"), "got: ${e.message}")
        }
    }

    @Test
    fun `wrong Bearer token is rejected with 401`() {
        val s = MockMcpServer.start {
            requireBearer("expected-token")
            tool("ping") { }
        }.also { toStop.add { it.stop() } }

        try {
            McpClient.connect(s.url, McpAuth.Bearer("wrong-token"))
            fail("expected 401 on initialize")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("401"), "got: ${e.message}")
        }
    }

    @Test
    fun `Bearer token is sent on every request not just initialize`() {
        val s = MockMcpServer.start {
            requireBearer("token-xyz")
            tool("ping") { respond { _ -> listOf(textBlock("pong")) } }
        }.also { toStop.add { it.stop() } }

        val client = McpClient.connect(s.url, McpAuth.Bearer("token-xyz")).also { toClose.add(it) }

        // If header weren't sent on tools/call, the mock would 401 here.
        repeat(3) { assertEquals("pong", client.call("ping", emptyMap())) }

        val headers = s.bearerTokensReceived()
        assertTrue(
            headers.size >= 4,
            "expected ≥4 authorized requests (initialize + 3 tools/call); got ${headers.size}: $headers",
        )
        assertTrue(headers.all { it == "token-xyz" }, "all tokens should match: $headers")
    }

    @Test
    fun `McpAuth None default does not send Authorization header`() {
        val s = MockMcpServer.start {
            tool("ping") { respond { _ -> listOf(textBlock("pong")) } }
        }.also { toStop.add { it.stop() } }

        val client = McpClient.connect(s.url).also { toClose.add(it) }
        assertEquals("pong", client.call("ping", emptyMap()))

        assertTrue(
            s.bearerTokensReceived().isEmpty(),
            "no Authorization header expected, saw: ${s.bearerTokensReceived()}",
        )
    }
}
