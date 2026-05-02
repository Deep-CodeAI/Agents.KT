package agents_engine.mcp

import agents_engine.core.agent
import agents_engine.generation.LenientJsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Conformance checks against the MCP 2025-03-26 specification.
 * Bypasses our McpClient and crafts raw JSON-RPC envelopes so we can verify
 * server-side behavior independently of client expectations.
 */
class McpServerConformanceTest {

    private val toStop = mutableListOf<() -> Unit>()
    private val http: HttpClient = HttpClient.newHttpClient()

    @AfterTest fun cleanup() { toStop.forEach { runCatching { it() } } }

    private fun trivialServer(): McpServer {
        val a = agent<String, String>("conf-test") {
            skills {
                skill<String, String>("noop", "no-op skill") { implementedBy { "ok" } }
            }
        }
        return McpServer.from(a) { expose("noop") }.start().also { toStop.add { it.stop() } }
    }

    private fun postJson(url: String, body: String, contentType: String = "application/json"): HttpResponse<String> {
        val req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", contentType)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        return http.send(req, HttpResponse.BodyHandlers.ofString())
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseEnvelope(payload: String): Map<String, Any?> =
        LenientJsonParser.parse(payload) as? Map<String, Any?>
            ?: error("not a JSON object: $payload")

    // ────────────────────────────────────────────────────────────
    // #618 — ping
    // ────────────────────────────────────────────────────────────

    // ────────────────────────────────────────────────────────────
    // #625 — plain prose description (no toLlmDescription markdown)
    // ────────────────────────────────────────────────────────────

    @Test
    fun `tools list description is the user-supplied prose without internal markdown headers`() {
        val a = agent<String, String>("plain-desc") {
            skills {
                skill<String, String>("greet", "Greets a person by name") {
                    implementedBy { "Hello, $it!" }
                }
            }
        }
        val server = McpServer.from(a) { expose("greet") }.start().also { toStop.add { it.stop() } }

        val response = postJson(server.url, """{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")
        val env = parseEnvelope(response.body())
        val result = env["result"] as? Map<*, *>
        assertNotNull(result)
        val tools = result["tools"] as? List<*>
        assertNotNull(tools)
        val tool = tools.single() as Map<*, *>
        val desc = tool["description"] as? String
        assertEquals("Greets a person by name", desc, "description should be the raw skill.description prose, not toLlmDescription")
        assertTrue("## Skill" !in (desc ?: ""), "no markdown header should appear in description: $desc")
        assertTrue("**Input:**" !in (desc ?: ""), "no input markup should appear in description: $desc")
    }

    // ────────────────────────────────────────────────────────────
    // #624 — Mcp-Session-Id on initialize
    // ────────────────────────────────────────────────────────────

    @Test
    fun `initialize response includes a non-empty Mcp-Session-Id header`() {
        val server = trivialServer()
        val response = postJson(
            server.url,
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"$MCP_PROTOCOL_VERSION","capabilities":{},"clientInfo":{"name":"t","version":"0"}}}""",
        )
        val sessionId = response.headers().firstValue("Mcp-Session-Id").orElse(null)
        assertNotNull(sessionId, "initialize response must carry Mcp-Session-Id")
        assertTrue(sessionId.isNotBlank(), "session id must be non-empty")
    }

    @Test
    fun `each server instance issues a different Mcp-Session-Id`() {
        val a = trivialServer()
        val b = trivialServer()
        val initBody =
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"$MCP_PROTOCOL_VERSION","capabilities":{},"clientInfo":{"name":"t","version":"0"}}}"""
        val sa = postJson(a.url, initBody).headers().firstValue("Mcp-Session-Id").orElse(null)
        val sb = postJson(b.url, initBody).headers().firstValue("Mcp-Session-Id").orElse(null)
        assertNotNull(sa); assertNotNull(sb)
        assertTrue(sa != sb, "different server instances should issue different session ids: $sa vs $sb")
    }

    @Test
    fun `subsequent requests work both with and without the session id`() {
        val server = trivialServer()
        val initResp = postJson(
            server.url,
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"$MCP_PROTOCOL_VERSION","capabilities":{},"clientInfo":{"name":"t","version":"0"}}}""",
        )
        val sessionId = initResp.headers().firstValue("Mcp-Session-Id").orElse(null)!!

        // With session id
        val withSession = HttpRequest.newBuilder()
            .uri(URI.create(server.url))
            .header("Content-Type", "application/json")
            .header("Mcp-Session-Id", sessionId)
            .POST(HttpRequest.BodyPublishers.ofString("""{"jsonrpc":"2.0","id":2,"method":"ping"}"""))
            .build()
        assertEquals(200, http.send(withSession, HttpResponse.BodyHandlers.ofString()).statusCode())

        // Without session id
        val withoutSession = postJson(server.url, """{"jsonrpc":"2.0","id":3,"method":"ping"}""")
        assertEquals(200, withoutSession.statusCode())
    }

    // ────────────────────────────────────────────────────────────
    // #623 — 405 for non-POST methods
    // ────────────────────────────────────────────────────────────

    @Test
    fun `GET on mcp endpoint returns 405 with Allow POST header`() {
        val server = trivialServer()
        val req = HttpRequest.newBuilder().uri(URI.create(server.url)).GET().build()
        val response = http.send(req, HttpResponse.BodyHandlers.ofString())

        assertEquals(405, response.statusCode())
        assertEquals("POST", response.headers().firstValue("Allow").orElse(null))
    }

    @Test
    fun `PUT and DELETE return 405`() {
        val server = trivialServer()
        for (method in listOf("PUT", "DELETE", "PATCH")) {
            val req = HttpRequest.newBuilder()
                .uri(URI.create(server.url))
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString("{}"))
                .build()
            val response = http.send(req, HttpResponse.BodyHandlers.ofString())
            assertEquals(405, response.statusCode(), "$method should return 405")
            assertEquals("POST", response.headers().firstValue("Allow").orElse(null), "$method should advertise Allow: POST")
        }
    }

    // ────────────────────────────────────────────────────────────
    // #622 — Content-Type validation
    // ────────────────────────────────────────────────────────────

    @Test
    fun `POST with application_json Content-Type is accepted`() {
        val server = trivialServer()
        val response = postJson(server.url, """{"jsonrpc":"2.0","id":1,"method":"ping"}""", "application/json")
        assertEquals(200, response.statusCode())
    }

    @Test
    fun `POST with text_plain Content-Type returns 415`() {
        val server = trivialServer()
        val response = postJson(server.url, """{"jsonrpc":"2.0","id":1,"method":"ping"}""", "text/plain")
        assertEquals(415, response.statusCode())
    }

    @Test
    fun `POST with no Content-Type header returns 415`() {
        val server = trivialServer()
        val req = HttpRequest.newBuilder()
            .uri(URI.create(server.url))
            .POST(HttpRequest.BodyPublishers.ofString("""{"jsonrpc":"2.0","id":1,"method":"ping"}"""))
            .build()
        val response = http.send(req, HttpResponse.BodyHandlers.ofString())
        assertEquals(415, response.statusCode())
    }

    // ────────────────────────────────────────────────────────────
    // #621 — tools/list pagination shape
    // ────────────────────────────────────────────────────────────

    @Test
    fun `tools list with cursor parameter does not error and returns nextCursor null`() {
        val server = trivialServer()
        val response = postJson(
            server.url,
            """{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{"cursor":"some-opaque-string"}}""",
        )
        val env = parseEnvelope(response.body())
        assertNull(env["error"], "tools/list should accept cursor without erroring: $env")
        val result = env["result"] as? Map<*, *>
        assertNotNull(result)
        assertTrue(
            "nextCursor" in result,
            "result should explicitly carry nextCursor (null = end of list), got keys: ${result.keys}",
        )
        assertNull(result["nextCursor"], "we have no pagination, nextCursor must be null, got: ${result["nextCursor"]}")
    }

    // ────────────────────────────────────────────────────────────
    // #620 — protocolVersion negotiation
    // ────────────────────────────────────────────────────────────

    @Test
    fun `initialize with matching protocolVersion succeeds and echoes our version`() {
        val server = trivialServer()
        val response = postJson(
            server.url,
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"$MCP_PROTOCOL_VERSION","capabilities":{},"clientInfo":{"name":"t","version":"0"}}}""",
        )
        val env = parseEnvelope(response.body())
        assertNull(env["error"], "should not error on matching version: $env")
        val result = env["result"] as? Map<*, *>
        assertNotNull(result)
        assertEquals(MCP_PROTOCOL_VERSION, result["protocolVersion"])
    }

    @Test
    fun `initialize with unsupported protocolVersion returns JSON-RPC error`() {
        val server = trivialServer()
        val response = postJson(
            server.url,
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"9999-01-01","capabilities":{},"clientInfo":{"name":"t","version":"0"}}}""",
        )
        val env = parseEnvelope(response.body())
        assertNull(env["result"], "should not return result on unsupported version: $env")
        val err = env["error"] as? Map<*, *>
        assertNotNull(err, "error envelope expected, got: $env")
        assertTrue(
            (err["message"] as? String)?.contains("9999-01-01") == true ||
                (err["message"] as? String)?.contains("unsupported", ignoreCase = true) == true,
            "error message should mention the unsupported version, got: $err",
        )
    }

    // ────────────────────────────────────────────────────────────
    // #619 — capabilities declaration
    // ────────────────────────────────────────────────────────────

    @Test
    fun `initialize declares tools capability with listChanged false explicitly`() {
        val server = trivialServer()
        val response = postJson(
            server.url,
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"$MCP_PROTOCOL_VERSION","capabilities":{},"clientInfo":{"name":"t","version":"0"}}}""",
        )
        val env = parseEnvelope(response.body())
        val result = env["result"] as? Map<*, *>
        assertNotNull(result)
        val caps = result["capabilities"] as? Map<*, *>
        assertNotNull(caps, "capabilities object missing")
        val tools = caps["tools"] as? Map<*, *>
        assertNotNull(tools, "tools capability missing")
        assertEquals(false, tools["listChanged"], "listChanged should be explicitly false, got: $tools")
    }

    @Test
    fun `ping returns empty result with matching id`() {
        val server = trivialServer()
        val response = postJson(server.url, """{"jsonrpc":"2.0","id":99,"method":"ping","params":{}}""")
        assertEquals(200, response.statusCode())

        val env = parseEnvelope(response.body())
        assertEquals("2.0", env["jsonrpc"])
        assertEquals(99, (env["id"] as? Number)?.toInt())
        val result = env["result"] as? Map<*, *>
        assertNotNull(result, "ping must return a result object, got: $env")
        assertTrue(result.isEmpty(), "ping result should be empty, got: $result")
        assertNull(env["error"], "ping should not error")
    }
}
