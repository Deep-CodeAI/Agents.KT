package agents_engine.mcp

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FibonacciMcpServerTest {

    private val servers = mutableListOf<MockMcpServer>()

    @AfterTest fun stopAll() { servers.forEach { it.stop() } }

    private fun start(): MockMcpServer = FibonacciMcpServer.start().also { servers.add(it) }

    @Test
    fun `fib_next called repeatedly returns the canonical sequence`() {
        val client = McpClient.connect(start().url)

        val seq = (1..6).map { client.call("fib_next", emptyMap()).toString().trim() }

        assertEquals(listOf("1", "1", "2", "3", "5", "8"), seq)
    }

    @Test
    fun `independent server instances have independent state`() {
        val a = McpClient.connect(start().url)
        val b = McpClient.connect(start().url)

        a.call("fib_next", emptyMap())
        a.call("fib_next", emptyMap())
        a.call("fib_next", emptyMap())  // a is at 2

        val firstFromB = b.call("fib_next", emptyMap()).toString().trim()

        assertEquals("1", firstFromB, "b's first call should be 1, not affected by a's state")
    }

    @Test
    fun `tools list exposes a single fib_next tool`() {
        val tools = McpClient.connect(start().url).toolDefs()

        assertEquals(1, tools.size)
        assertEquals("fib_next", tools.single().name)
        assertTrue(tools.single().description.isNotBlank(), "fib_next should have a description")
    }
}
