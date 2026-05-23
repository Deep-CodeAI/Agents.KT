package agents_engine.mcp

import agents_engine.core.Decision
import agents_engine.core.agent
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpServerBeforeInterceptorTest {

    @Test
    fun `tools-call Deny returns MCP tool error without executing exposed skill`() {
        var executed = false
        val a = agent<String, String>("mcp-policy") {
            skills {
                skill<String, String>("echo", "Echo input") {
                    implementedBy {
                        executed = true
                        it
                    }
                }
            }
        }
        a.onBeforeToolCall { _, args ->
            if (args["input"] == "secret") Decision.Deny("denied by policy")
            else Decision.Proceed
        }

        val server = McpServer.from(a) { expose("echo") }
        val response = server.dispatchJsonRpc(
            """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"echo","arguments":{"input":"secret"}}}"""
        )!!

        assertFalse(executed)
        assertTrue(response.contains("denied by policy"), response)
        assertTrue(response.contains(""""isError":true"""), response)
    }

    @Test
    fun `tools-call ProceedWith mutates arguments before exposed skill deserialization`() {
        val a = agent<String, String>("mcp-mutate") {
            skills {
                skill<String, String>("echo", "Echo input") {
                    implementedBy { it }
                }
            }
        }
        a.onBeforeToolCall { _, args ->
            Decision.ProceedWith(args + ("input" to "mutated"))
        }

        val server = McpServer.from(a) { expose("echo") }
        val response = server.dispatchJsonRpc(
            """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"echo","arguments":{"input":"original"}}}"""
        )!!

        assertTrue(response.contains("mutated"), response)
        assertTrue(response.contains(""""isError":false"""), response)
    }
}
