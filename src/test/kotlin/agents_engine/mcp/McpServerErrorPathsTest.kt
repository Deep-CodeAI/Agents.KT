package agents_engine.mcp

import agents_engine.core.agent
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Tests for #889 (catch-all) — McpServer error-path coverage.
//
// PIT NO_COVERAGE clusters in McpServer.handle / handleToolCall:
// - L86: malformed JSON → 400
// - L87: missing "method" → 400
// - L107: internal exception → 500 (in the outer catch)
// - L133: missing tool name in tools/call → -32602
// - L135: unknown tool name → -32601
// - L148: skill execution throws → isError:true response
class McpServerErrorPathsTest {

    private val toStop = mutableListOf<() -> Unit>()

    @AfterTest fun cleanup() { toStop.forEach { runCatching { it() } } }

    private fun trivialAgent() = agent<String, String>("greeter") {
        skills { skill<String, String>("greet", "Greets") { implementedBy { "hi $it" } } }
    }

    private fun explodingAgent() = agent<String, String>("boomer") {
        skills {
            skill<String, String>("boom", "Always throws") {
                implementedBy { _ -> throw RuntimeException("kaboom") }
            }
        }
    }

    private fun startServer(agent: agents_engine.core.Agent<*, *>, exposed: List<String>): McpServer {
        val server = McpServer.from(agent) { exposed.forEach { expose(it) }; port = 0 }.start()
        toStop.add { server.stop() }
        return server
    }

    private fun postRaw(url: String, body: String): HttpResponse<String> {
        val req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        return HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString())
    }

    // L86 — malformed JSON body

    @Test
    fun `malformed JSON body returns 400`() {
        val server = startServer(trivialAgent(), listOf("greet"))
        val r = postRaw(server.url, "not json at all")
        assertEquals(400, r.statusCode())
    }

    // L87 — JSON without "method" field

    @Test
    fun `JSON without method field returns 400`() {
        val server = startServer(trivialAgent(), listOf("greet"))
        val r = postRaw(server.url, """{"jsonrpc":"2.0","id":1}""")
        assertEquals(400, r.statusCode())
    }

    // L107 — internal exception path. Hard to trigger directly (the outer
    // catch wraps anything that escapes the dispatcher). Sending a method
    // that the dispatcher can handle but with malformed `params` won't
    // exercise it because handlers tolerate empty params. Using a
    // `notifications/`-prefixed method exits early. The cleanest reachable
    // case: a method that LooksLikeJsonButIsn't valid for the parser
    // mid-deserialization. Skipping this branch — it's defensively wrapping
    // the entire dispatcher and only fires on truly unexpected runtime
    // errors. Documented but not exercised.

    // L133 — tools/call without "name" parameter

    @Test
    fun `tools-call without name returns JSON-RPC error code -32602`() {
        val server = startServer(trivialAgent(), listOf("greet"))
        val r = postRaw(
            server.url,
            """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{}}""",
        )
        assertEquals(200, r.statusCode())
        assertTrue(r.body().contains("\"code\":-32602"), "body: ${r.body()}")
        assertTrue(
            r.body().contains("Missing tool name", ignoreCase = true),
            "body: ${r.body()}",
        )
    }

    // L135 — tools/call with unknown tool name

    @Test
    fun `tools-call with unknown tool name returns JSON-RPC error code -32601`() {
        val server = startServer(trivialAgent(), listOf("greet"))
        val r = postRaw(
            server.url,
            """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"nonexistent"}}""",
        )
        assertEquals(200, r.statusCode())
        assertTrue(r.body().contains("\"code\":-32601"), "body: ${r.body()}")
        assertTrue(r.body().contains("nonexistent"), "body: ${r.body()}")
    }

    // L148 — skill execution throws → isError:true response

    @Test
    fun `tools-call where skill throws returns isError true with the exception message`() {
        val server = startServer(explodingAgent(), listOf("boom"))
        val r = postRaw(
            server.url,
            """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"boom","arguments":{"input":"hi"}}}""",
        )
        assertEquals(200, r.statusCode(), "body: ${r.body()}")
        assertTrue(r.body().contains("\"isError\":true"), "must mark isError true; body: ${r.body()}")
        assertTrue(r.body().contains("kaboom"), "must include exception message; body: ${r.body()}")
    }

    // Bonus — sanity that ordinary methods still work alongside these error tests.

    @Test
    fun `unknown top-level method returns -32601 method not found`() {
        val server = startServer(trivialAgent(), listOf("greet"))
        val r = postRaw(
            server.url,
            """{"jsonrpc":"2.0","id":1,"method":"completely/unknown"}""",
        )
        assertEquals(200, r.statusCode())
        assertTrue(r.body().contains("\"code\":-32601"), "body: ${r.body()}")
        assertTrue(r.body().contains("Method not found"), "body: ${r.body()}")
    }
}
