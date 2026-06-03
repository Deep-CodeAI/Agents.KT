package agents_engine.mcp

import agents_engine.core.Agent
import agents_engine.generation.LenientJsonParser
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress

/**
 * `agents_engine/mcp/McpServer.kt` — exposes an [Agent]'s skills as MCP
 * tools (and prompts/resources per #1796) over Streamable HTTP. The
 * transport-agnostic JSON-RPC protocol core lives in [McpDispatcher] (#2795);
 * this class owns only HTTP intake — lifecycle, inbound auth, Host+Origin
 * validation, body framing — and hands parsed requests to the dispatcher.
 * Stdio hosting ([McpStdioServer]) drives the same dispatcher directly.
 * Built via `McpServer.from(agent) { expose(...) }`. Scope:
 * HTTP (JDK `HttpServer`) with inbound auth / Host+Origin validation /
 * per-principal tool policy; non-agentic skills only (declared via
 * `implementedBy { }`); skill `IN` must be `String` or a `@Generable`
 * class. Server-side prompts mirror MCP wire shape (RegisteredPrompt).
 * Incoming `tools/call` requests are policy-gated and pass through the
 * source agent's `onBeforeToolCall` decision chain before skill execution.
 * The InternalsAgent itself runs on this. See
 * `src/main/resources/internals-agent/mcp/McpServer.md` (#1837 / #1884).
 */

/**
 * Exposes an [Agent]'s skills as MCP tools over Streamable HTTP.
 *
 * ```kotlin
 * val server = McpServer.from(coder) {
 *     port = 8080         // 0 = auto-assign
 *     expose("write-code")
 *     auth = McpServerAuth.RequireBearerToken(token)
 * }.start()
 * ```
 *
 * Scope:
 * - HTTP transport here (uses JDK [HttpServer]); [McpStdioServer] reuses the
 *   shared [McpDispatcher] for server-side stdio.
 * - Non-agentic skills only (skills declared via `implementedBy { }`).
 *   Agentic skills require server-side LLM access — out of scope here.
 * - Skill `IN` must be `String` or a `@Generable` class. Other types rejected at [start].
 * - Skill output rendered as a single text content block (`toString()`).
 * - HTTP callers are authenticated before JSON-RPC dispatch. The default
 *   [McpServerAuth.TrustedLocal] accepts loopback clients and rejects
 *   non-local clients; bearer auth is available for network-reachable use.
 */
class McpServer private constructor(
    private val dispatcher: McpDispatcher,
    private val portRequest: Int,
    private val maxRequestBytes: Long = DEFAULT_MAX_REQUEST_BYTES,
    private val auth: McpServerAuth = McpServerAuth.TrustedLocal,
    private val allowedHosts: Set<String> = emptySet(),
    private val originAllowlist: Set<String> = emptySet(),
) {
    private var http: HttpServer? = null

    val agent: Agent<*, *> get() = dispatcher.agent

    val url: String
        get() = http?.let { "http://localhost:${it.address.port}/mcp" }
            ?: error("McpServer not started")

    fun start(): McpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", portRequest), 0)
        server.createContext("/mcp") { handle(it) }
        server.executor = null
        server.start()
        http = server
        return this
    }

    fun stop() { http?.stop(0); http = null }

    fun isRunning(): Boolean = http != null

    fun snapshotFor(principal: ClientPrincipal): McpServerInfo = dispatcher.snapshotFor(principal)

    private fun handle(exchange: HttpExchange) {
        try {
            val principal = authenticate(exchange) ?: return
            if (!validateAllowedHost(exchange) || !validateAllowedOrigin(exchange)) return
            if (!validateRequest(exchange)) return
            val bodyBytes = readBoundedBody(exchange) ?: return
            val bodyText = String(bodyBytes, Charsets.UTF_8)
            val request = LenientJsonParser.parse(bodyText) as? Map<*, *>
                ?: return respond(exchange, 400, "{}")
            val method = request["method"] as? String ?: return respond(exchange, 400, "{}")

            // HTTP framing that needs the parsed method before business dispatch:
            // notifications / id-less requests are acknowledged with 202, and the
            // session id is surfaced on the initialize response.
            if (!request.containsKey("id") || method.startsWith("notifications/")) {
                respond(exchange, 202, "")
                return
            }
            if (method == "initialize") exchange.responseHeaders.add("Mcp-Session-Id", dispatcher.sessionId)
            respond(exchange, 200, dispatcher.dispatchRequest(request, principal))
        } catch (e: Exception) {
            respond(exchange, 500, """{"error":${McpJson.encode(e.message ?: e.toString())}}""")
        } finally {
            exchange.close()
        }
    }

    /**
     * Method + content-type intake guards. Responds (405 / 415) and returns false on rejection so
     * [handle] stays short orchestration (#2795).
     */
    private fun validateRequest(exchange: HttpExchange): Boolean {
        if (exchange.requestMethod != "POST") {
            exchange.responseHeaders.add("Allow", "POST")
            respond(exchange, 405, """{"error":"Method Not Allowed — only POST is supported"}""")
            return false
        }
        val ct = exchange.requestHeaders.getFirst("Content-Type")
        if (ct == null || !ct.startsWith("application/json")) {
            respond(exchange, 415, """{"error":"Unsupported Media Type — expected application/json"}""")
            return false
        }
        return true
    }

    /**
     * #851 — bound the request body before reading. Honors Content-Length when present; falls back to
     * a length-bounded read otherwise. Avoids OOM from a same-host process posting a multi-GB body to
     * the loopback server. Responds 413 and returns null when the cap is exceeded (#2795).
     */
    private fun readBoundedBody(exchange: HttpExchange): ByteArray? {
        val declaredLength = exchange.requestHeaders.getFirst("Content-Length")?.toLongOrNull()
        if (declaredLength != null && declaredLength > maxRequestBytes) {
            respond(exchange, 413, """{"error":"Payload Too Large — limit is $maxRequestBytes bytes"}""")
            return null
        }
        val cap = maxRequestBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val bodyBytes = exchange.requestBody.use { it.readNBytes(cap + 1) }
        if (bodyBytes.size > cap) {
            respond(exchange, 413, """{"error":"Payload Too Large — limit is $maxRequestBytes bytes"}""")
            return null
        }
        return bodyBytes
    }

    private fun authenticate(exchange: HttpExchange): ClientPrincipal? {
        val context = McpHttpRequestContext(
            headers = exchange.requestHeaders.mapValues { it.value.toList() },
            remoteAddress = exchange.remoteAddress?.address?.hostAddress,
        )
        return when (val decision = auth.authenticate(context)) {
            is McpAuthDecision.Allow -> decision.principal
            is McpAuthDecision.Reject -> {
                respond(exchange, decision.statusCode, """{"error":${McpJson.encode(decision.message)}}""")
                null
            }
        }
    }

    private fun validateAllowedHost(exchange: HttpExchange): Boolean {
        if (allowedHosts.isEmpty()) return true
        val host = exchange.requestHeaders.getFirst("Host")
        if (host != null && allowedHosts.any { hostMatches(host, it) }) return true
        respond(exchange, 403, """{"error":"Forbidden — Host is not allowed"}""")
        return false
    }

    private fun validateAllowedOrigin(exchange: HttpExchange): Boolean {
        if (originAllowlist.isEmpty()) return true
        val origin = exchange.requestHeaders.getFirst("Origin")
        if (origin != null && originAllowlist.any { it.equals(origin, ignoreCase = true) }) return true
        respond(exchange, 403, """{"error":"Forbidden — Origin is not allowed"}""")
        return false
    }

    private fun respond(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
    }

    companion object {
        // 8 MiB — generous for tools/call payloads, far short of OOM on a typical
        // JVM heap. See #851.
        const val DEFAULT_MAX_REQUEST_BYTES: Long = 8L * 1024 * 1024

        fun from(agent: Agent<*, *>, block: McpExposeBuilder.() -> Unit): McpServer {
            val builder = McpExposeBuilder().apply(block)
            return McpServer(
                dispatcher = McpDispatcher.build(agent, builder),
                portRequest = builder.port,
                maxRequestBytes = builder.maxRequestBytes,
                auth = builder.auth,
                allowedHosts = builder.allowedHosts,
                originAllowlist = builder.originAllowlist,
            )
        }
    }
}

private fun hostMatches(actual: String, allowed: String): Boolean {
    if (actual.equals(allowed, ignoreCase = true)) return true
    return hostOnly(actual).equals(hostOnly(allowed), ignoreCase = true)
}

private fun hostOnly(value: String): String {
    val trimmed = value.trim()
        .removePrefix("http://")
        .removePrefix("https://")
        .substringBefore('/')
    return when {
        trimmed.startsWith("[") -> trimmed.substringAfter('[').substringBefore(']')
        else -> trimmed.substringBefore(':')
    }
}
