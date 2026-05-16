package agents_engine.mcp

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * `agents_engine/mcp/HttpMcpTransport.kt` — Streamable HTTP transport
 * for MCP. Each `rpc()` is a POST whose response is JSON or SSE
 * (single `data:` event). Captures `Mcp-Session-Id` from any response
 * and replays it on subsequent requests. Honors [McpAuth] (Bearer,
 * None) and per-request / max-response-size limits. See
 * `src/main/resources/internals-agent/mcp/HttpMcpTransport.md`
 * (#1837 / #1877).
 */

/**
 * Streamable HTTP transport. Each `rpc()` is a POST whose response is either a JSON body
 * or an SSE stream (a single `data:` event). Captures `Mcp-Session-Id` from any response
 * header and replays it on subsequent requests.
 */
internal class HttpMcpTransport(
    private val url: String,
    private val auth: McpAuth = McpAuth.None,
    private val requestTimeout: Duration = DEFAULT_REQUEST_TIMEOUT,
    private val maxResponseBytes: Long = DEFAULT_MAX_RESPONSE_BYTES,
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
            .timeout(requestTimeout.toJavaDuration())
            .also { if (sessionId != null) it.header("Mcp-Session-Id", sessionId!!) }
            .also { applyAuth(it) }
            .POST(HttpRequest.BodyPublishers.ofString(envelope))
        // #853 — bounded read so a malicious upstream MCP server can't OOM us.
        val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
        val cap = maxResponseBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val bytes = response.body().use { it.readNBytes(cap + 1) }
        if (bytes.size > cap) {
            error("MCP response exceeded $maxResponseBytes bytes; aborting to prevent OOM")
        }
        val bodyStr = String(bytes, Charsets.UTF_8)
        if (response.statusCode() !in 200..299) {
            error("MCP HTTP ${response.statusCode()}: $bodyStr")
        }
        response.headers().firstValue("mcp-session-id").ifPresent { sessionId = it }
        if (!expectBody) return ""
        val ct = response.headers().firstValue("content-type").orElse("")
        return if (ct.startsWith("text/event-stream")) {
            extractSseJson(bodyStr)
                ?: error("MCP SSE response had no JSON data event: $bodyStr")
        } else bodyStr
    }

    private fun applyAuth(builder: HttpRequest.Builder) {
        when (val a = auth) {
            is McpAuth.None -> { /* no header */ }
            is McpAuth.Bearer -> builder.header("Authorization", "Bearer ${a.token}")
        }
    }

    companion object {
        // See #852.
        val DEFAULT_REQUEST_TIMEOUT: Duration = 60.seconds
        val DEFAULT_CONNECT_TIMEOUT: Duration = 10.seconds

        // 8 MiB — MCP responses are typically small JSON-RPC envelopes. See #853.
        const val DEFAULT_MAX_RESPONSE_BYTES: Long = 8L * 1024 * 1024

        private val http: HttpClient = HttpClient.newBuilder()
            .connectTimeout(DEFAULT_CONNECT_TIMEOUT.toJavaDuration())
            .build()

        private fun extractSseJson(body: String): String? =
            body.lineSequence()
                .filter { it.startsWith("data:") }
                .map { it.removePrefix("data:").trim() }
                .firstOrNull { it.startsWith("{") }
    }
}
