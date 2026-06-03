package agents_engine.mcp

import agents_engine.core.agent
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #2795 — pins the transport-agnostic JSON-RPC core extracted out of the McpServer god class into
 * [McpDispatcher]. The same dispatcher backs both HTTP intake and stdio; these assert the
 * envelope-level contract stdio relies on (formerly the `McpServer.dispatchJsonRpc` back door).
 */
class McpDispatcherTest {

    private fun dispatcher() = McpDispatcher.from(
        agent<String, String>("d") {
            skills { skill<String, String>("echo", "Echo") { implementedBy { it } } }
        },
    ) { expose("echo") }

    @Test
    fun `a notification (no id) produces no response`() {
        val response = dispatcher().dispatchEnvelope(
            """{"jsonrpc":"2.0","method":"notifications/initialized"}""",
        )
        assertNull(response, "notifications must not produce a response envelope")
    }

    @Test
    fun `a malformed envelope yields a JSON-RPC parse error`() {
        val response = dispatcher().dispatchEnvelope("not json")!!
        assertTrue(response.contains("Parse error"), response)
    }

    @Test
    fun `tools-call dispatches to the exposed skill`() {
        val response = dispatcher().dispatchEnvelope(
            """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"echo","arguments":{"input":"hi"}}}""",
        )!!
        assertTrue(response.contains("hi"), response)
        assertTrue(response.contains(""""isError":false"""), response)
    }
}
