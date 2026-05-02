package agents_engine.mcp

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Streamable HTTP transport. Each `rpc()` is a POST whose response is either a JSON body
 * or an SSE stream (a single `data:` event). Captures `Mcp-Session-Id` from any response
 * header and replays it on subsequent requests.
 */
internal class HttpMcpTransport(
    private val url: String,
    private val auth: McpAuth = McpAuth.None,
) : McpTransport {

    private var sessionId: String? = null

    override fun rpc(envelope: String): String = post(envelope, expectBody = true)

    override fun notify(envelope: String) { post(envelope, expectBody = false) }

    override fun close() { /* shared HttpClient — nothing per-transport */ }

    private fun post(envelope: String, expectBody: Boolean): String {
        val builder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .also { if (sessionId != null) it.header("Mcp-Session-Id", sessionId!!) }
            .also { applyAuth(it) }
            .POST(HttpRequest.BodyPublishers.ofString(envelope))
        val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            error("MCP HTTP ${response.statusCode()}: ${response.body()}")
        }
        response.headers().firstValue("mcp-session-id").ifPresent { sessionId = it }
        if (!expectBody) return ""
        val ct = response.headers().firstValue("content-type").orElse("")
        return if (ct.startsWith("text/event-stream")) {
            extractSseJson(response.body())
                ?: error("MCP SSE response had no JSON data event: ${response.body()}")
        } else response.body()
    }

    private fun applyAuth(builder: HttpRequest.Builder) {
        when (val a = auth) {
            is McpAuth.None -> { /* no header */ }
            is McpAuth.Bearer -> builder.header("Authorization", "Bearer ${a.token}")
        }
    }

    companion object {
        private val http: HttpClient = HttpClient.newHttpClient()

        private fun extractSseJson(body: String): String? =
            body.lineSequence()
                .filter { it.startsWith("data:") }
                .map { it.removePrefix("data:").trim() }
                .firstOrNull { it.startsWith("{") }
    }
}
