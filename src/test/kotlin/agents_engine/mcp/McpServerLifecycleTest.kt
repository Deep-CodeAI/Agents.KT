package agents_engine.mcp

import agents_engine.core.agent
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.assertFalse

// Tests for #889 — McpServer lifecycle invariants that were uncovered by the
// existing conformance / error-path tests. Each test targets one
// "removed-call" or "boundary" mutant in a specific lifecycle method.
//
// Killed mutants:
// - L82: `error("McpServer not started")` — must throw when `http` is null
// - L84-90: `start()` must return `this` (chainability)
// - L93: `stop()` must null out `http` AND must be idempotent
// - L95: `isRunning()` must reflect started / stopped state, not always true/false
class McpServerLifecycleTest {

    private val toStop = mutableListOf<() -> Unit>()
    @AfterTest fun cleanup() { toStop.forEach { runCatching { it() } } }

    private fun trivialAgent() = agent<String, String>("greeter") {
        skills { skill<String, String>("greet", "Greets") { implementedBy { "hi $it" } } }
    }

    private fun newServer() = McpServer.from(trivialAgent()) {
        expose("greet")
        port = 0
    }

    @Test
    fun `url getter throws with clear message when server not started`() {
        val server = newServer()
        val e = assertFails { server.url }
        assertNotNull(e.message, "exception must carry a message")
        assertTrue(
            e.message!!.contains("not started", ignoreCase = true),
            "message should explain the lifecycle problem: '${e.message}'",
        )
    }

    @Test
    fun `isRunning is false before start and true after start`() {
        val server = newServer()
        assertFalse(server.isRunning(), "isRunning must be false on a freshly-built server")
        server.start()
        toStop.add { server.stop() }
        assertTrue(server.isRunning(), "isRunning must be true after start() returns")
    }

    @Test
    fun `isRunning is false after stop`() {
        val server = newServer().start()
        assertTrue(server.isRunning())
        server.stop()
        assertFalse(server.isRunning(), "isRunning must be false after stop()")
    }

    @Test
    fun `url getter throws again after stop`() {
        val server = newServer().start()
        // Sanity — url works while running.
        assertNotNull(server.url)
        server.stop()
        val e = assertFails { server.url }
        assertTrue(
            (e.message ?: "").contains("not started", ignoreCase = true),
            "post-stop url should fail with the same not-started message: '${e.message}'",
        )
    }

    @Test
    fun `start returns the same server instance for chaining`() {
        val server = newServer()
        val returned = server.start()
        toStop.add { server.stop() }
        assertSame(server, returned, "start() must return `this` so `McpServer.from(...) { }.start()` chains")
    }

    @Test
    fun `stop is idempotent — calling twice does not throw`() {
        val server = newServer().start()
        server.stop()
        // Second stop is what would throw on a non-idempotent implementation.
        server.stop()
        assertFalse(server.isRunning(), "double-stop must leave server in stopped state")
    }

    @Test
    fun `url contains the bound port and the mcp path`() {
        val server = newServer().start()
        toStop.add { server.stop() }
        val url = server.url
        assertTrue(url.startsWith("http://localhost:"), "url should start with http://localhost: but was '$url'")
        assertTrue(url.endsWith("/mcp"), "url should end with /mcp but was '$url'")
        // Port must be a real positive integer (port=0 → OS-assigned, never 0 after binding).
        val port = url.substringAfter("http://localhost:").substringBefore("/mcp").toInt()
        assertTrue(port > 0, "bound port must be > 0 (OS-assigned), got $port")
    }

    @Test
    fun `port 0 produces different OS-assigned ports across instances`() {
        // Catches "bind always returns same port" mutants and gives a meaningful
        // assertion that the OS-assigned-port path actually does what it claims.
        val a = newServer().start(); toStop.add { a.stop() }
        val b = newServer().start(); toStop.add { b.stop() }
        val portA = a.url.substringAfter("http://localhost:").substringBefore("/mcp").toInt()
        val portB = b.url.substringAfter("http://localhost:").substringBefore("/mcp").toInt()
        assertEquals(
            2, setOf(portA, portB).size,
            "two port=0 servers must get distinct OS-assigned ports; got $portA and $portB",
        )
    }
}
