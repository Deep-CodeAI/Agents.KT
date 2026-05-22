package agents_engine.mcp

import agents_engine.core.agent
import agents_engine.generation.LenientJsonParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class McpStdioServerTest {

    @Test
    fun `stdio server handles initialize tools prompts resources notification and malformed input`() {
        val server = stdioServer()
        val stdout = serveLines(
            server,
            listOf(
                """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"$MCP_PROTOCOL_VERSION"}}""",
                """{"jsonrpc":"2.0","id":2,"method":"tools/list"}""",
                """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"greet","arguments":{"input":"Ada"}}}""",
                """{"jsonrpc":"2.0","method":"notifications/initialized"}""",
                """{"jsonrpc":"2.0","id":4,"method":"prompts/list"}""",
                """{"jsonrpc":"2.0","id":5,"method":"prompts/get","params":{"name":"hello","arguments":{"name":"Grace"}}}""",
                """{"jsonrpc":"2.0","id":6,"method":"resources/list"}""",
                """{"jsonrpc":"2.0","id":7,"method":"resources/read","params":{"uri":"memory://note"}}""",
                """not-json""",
                """{"jsonrpc":"2.0","id":8}""",
            ),
        )
        val responses = stdout.lines().filter { it.isNotBlank() }

        assertEquals(9, responses.size, "notification must not produce a response: $responses")
        responses.forEach { assertTrue(it.startsWith("{"), "stdout must be protocol JSON only: $it") }

        val initialize = responses[0].asMap()
        assertEquals(1, initialize["id"])
        val initResult = initialize["result"] as Map<*, *>
        assertEquals(MCP_PROTOCOL_VERSION, initResult["protocolVersion"])

        val tools = (((responses[1].asMap()["result"] as Map<*, *>)["tools"] as List<*>)
            .filterIsInstance<Map<*, *>>())
        assertEquals(listOf("greet"), tools.map { it["name"] })

        val toolCall = responses[2].asMap()["result"] as Map<*, *>
        val toolContent = (toolCall["content"] as List<*>).single() as Map<*, *>
        assertEquals("Hello, Ada!", toolContent["text"])

        val prompts = (((responses[3].asMap()["result"] as Map<*, *>)["prompts"] as List<*>)
            .filterIsInstance<Map<*, *>>())
        assertEquals(listOf("hello"), prompts.map { it["name"] })

        val promptGet = responses[4].asMap()["result"] as Map<*, *>
        val promptMessages = promptGet["messages"] as List<*>
        val promptContent = (promptMessages.single() as Map<*, *>)["content"] as Map<*, *>
        assertEquals("Hello Grace", promptContent["text"])

        val resources = (((responses[5].asMap()["result"] as Map<*, *>)["resources"] as List<*>)
            .filterIsInstance<Map<*, *>>())
        assertEquals(listOf("memory://note"), resources.map { it["uri"] })

        val resourceRead = responses[6].asMap()["result"] as Map<*, *>
        val resourceContent = (resourceRead["contents"] as List<*>).single() as Map<*, *>
        assertEquals("remember this", resourceContent["text"])

        val malformed = responses[7].asMap()
        assertEquals(null, malformed["id"])
        assertNotNull(malformed["error"], "malformed JSON must become a JSON-RPC error envelope")

        val missingMethod = responses[8].asMap()
        assertEquals(null, missingMethod["id"])
        assertNotNull(missingMethod["error"], "missing method must become a JSON-RPC error envelope")
    }

    @Test
    fun `stdio server treats request without id as notification`() {
        val stdout = serveLines(
            stdioServer(),
            listOf("""{"jsonrpc":"2.0","method":"ping"}"""),
        )

        assertEquals("", stdout, "requests without id are notifications and must not write stdout")
    }

    private fun stdioServer(): McpStdioServer {
        val greeter = agent<String, String>("greeter") {
            skills {
                skill<String, String>("greet", "Greets a user") {
                    implementedBy { input -> "Hello, $input!" }
                }
            }
        }
        return McpStdioServer.from(greeter) {
            expose("greet")
            prompt("hello", "Greeting prompt") { args -> "Hello ${args["name"]}" }
            resource(
                uri = "memory://note",
                name = "note",
                mimeType = "text/plain",
            ) { "remember this" }
        }
    }

    private fun serveLines(server: McpStdioServer, lines: List<String>): String {
        val input = lines.joinToString(separator = "\n", postfix = "\n")
            .byteInputStream(Charsets.UTF_8)
        val stdout = ByteArrayOutputStream()
        server.serve(ByteArrayInputStream(input.readBytes()), stdout)
        return stdout.toString(Charsets.UTF_8)
    }

    private fun String.asMap(): Map<*, *> =
        LenientJsonParser.parse(this) as? Map<*, *>
            ?: error("not a JSON object: $this")
}
