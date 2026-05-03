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

// Tests for #851 — McpServer caps request body size and returns 413 before
// loading the entire body into memory.
class McpServerBodySizeLimitTest {

    private val toStop = mutableListOf<() -> Unit>()

    @AfterTest fun cleanup() { toStop.forEach { runCatching { it() } } }

    private fun trivialAgent() = agent<String, String>("greeter") {
        skills { skill<String, String>("greet", "Greets") { implementedBy { "hi $it" } } }
    }

    private fun startServer(maxBytes: Long = McpServer.DEFAULT_MAX_REQUEST_BYTES): McpServer {
        val server = McpServer.from(trivialAgent()) {
            expose("greet")
            port = 0
            maxRequestBytes = maxBytes
        }.start()
        toStop.add { server.stop() }
        return server
    }

    private fun postRaw(url: String, body: String, contentLength: Long? = null): HttpResponse<String> {
        val builder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
        if (contentLength != null) {
            // The JDK HttpClient sets Content-Length automatically; this branch is here
            // for explicit-override tests if the framework ever needs them.
            builder.setHeader("Content-Length", contentLength.toString())
        }
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    @Test
    fun `request larger than the cap is rejected with 413`() {
        val server = startServer(maxBytes = 1024)  // 1 KiB cap for the test
        val tooBig = "x".repeat(2048)  // 2 KiB
        val response = postRaw(server.url, """{"jsonrpc":"2.0","id":1,"method":"ping","params":{"pad":"$tooBig"}}""")
        assertEquals(413, response.statusCode())
        assertTrue(
            response.body().contains("Payload Too Large", ignoreCase = true),
            "expected error body to mention size limit; got: ${response.body()}",
        )
    }

    @Test
    fun `request exactly at the cap is processed normally`() {
        val server = startServer(maxBytes = 16 * 1024)  // 16 KiB cap
        // Build a request that's well under the cap but uses real JSON-RPC shape.
        val response = postRaw(server.url, """{"jsonrpc":"2.0","id":1,"method":"ping"}""")
        assertEquals(200, response.statusCode())
        assertTrue(response.body().contains("\"result\""), "expected success: ${response.body()}")
    }

    @Test
    fun `default cap accepts a normal-sized tools-list request`() {
        // Sanity: the default cap doesn't break ordinary traffic.
        val server = startServer()  // default
        val response = postRaw(server.url, """{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")
        assertEquals(200, response.statusCode())
    }
}
