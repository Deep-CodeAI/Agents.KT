package agents_engine.nlweb

import agents_engine.generation.LenientJsonParser
import agents_engine.internal.toJsonString
import agents_engine.model.NlWebMode
import agents_engine.model.NlWebResult
import agents_engine.model.NlWebSearchResult
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.UUID

/**
 * `agents_engine/nlweb/NlWebServer.kt` — #4542 (PRD §12.9). Exposes an agents.kt retrieval source as
 * an [NLWeb](https://github.com/nlweb-ai/NLWeb) endpoint: a website's natural-language interface over
 * schema.org content. Follows the [agents_engine.a2a.A2AServer] / [agents_engine.mcp.McpServer]
 * precedent — JDK [HttpServer], **loopback-only** bind, optional bearer auth; front it with a gateway
 * for any network reach. This completes the NLWeb story's serve side (the `nlwebSearch` tool, #4541,
 * is the consume side).
 *
 * Surface (v1):
 * - `POST /ask` — body `{query, site?, mode, streaming}` → `{query_id, results:[{url, name, site,
 *   score, description, schema_object}], summary?}`. The [NlWebAskHandler] does the retrieval/ranking;
 *   the server is pure transport + the NLWeb wire mapping.
 *
 * Out of scope in v1: SSE streaming (`streaming` is accepted but ignored — the reply is one blob), and
 * the `/mcp` face (every NLWeb endpoint is also an MCP server — expose an `ask` skill via `McpServer`
 * for that; this server is the `/ask`-over-HTTP path).
 */
class NlWebServer private constructor(
    private val handler: NlWebAskHandler,
    private val portRequest: Int,
    private val bearerToken: String?,
    private val maxRequestBytes: Int,
) {
    private var http: HttpServer? = null

    val url: String
        get() = http?.let { "http://localhost:${it.address.port}/ask" } ?: error("NlWebServer not started")

    fun start(): NlWebServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", portRequest), 0)
        server.createContext("/ask") { exchange -> handleSafely(exchange) { handleAsk(exchange) } }
        server.executor = null
        server.start()
        http = server
        return this
    }

    fun stop() {
        http?.stop(0)
        http = null
    }

    private fun handleSafely(exchange: HttpExchange, block: () -> Unit) {
        try {
            if (!authorized(exchange)) {
                respond(exchange, HTTP_UNAUTHORIZED, """{"error":"Unauthorized"}""")
                return
            }
            block()
        } catch (e: Exception) {
            respond(exchange, HTTP_SERVER_ERROR, """{"error":${(e.message ?: e.toString()).toJsonString()}}""")
        } finally {
            exchange.close()
        }
    }

    private fun authorized(exchange: HttpExchange): Boolean {
        val expected = bearerToken ?: return true
        return exchange.requestHeaders.getFirst("Authorization") == "Bearer $expected"
    }

    private fun handleAsk(exchange: HttpExchange) {
        if (exchange.requestMethod != "POST") {
            respond(exchange, HTTP_METHOD_NOT_ALLOWED, """{"error":"use POST"}""")
            return
        }
        val body = exchange.requestBody.use { it.readNBytes(maxRequestBytes) }.toString(Charsets.UTF_8)
        val root = LenientJsonParser.parse(body) as? Map<*, *>
            ?: return respond(exchange, HTTP_BAD_REQUEST, """{"error":"request body is not a JSON object"}""")
        val query = root["query"] as? String
        if (query.isNullOrBlank()) {
            return respond(exchange, HTTP_BAD_REQUEST, """{"error":"missing 'query'"}""")
        }
        val request = NlWebAskRequest(
            query = query,
            site = root["site"] as? String,
            mode = parseMode(root["mode"] as? String),
        )
        respond(exchange, HTTP_OK, renderAskResponse(handler.ask(request)))
    }

    private fun respond(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    companion object {
        /**
         * Expose [handler] as an NLWeb `/ask` endpoint. Binds loopback-only (front with a gateway for
         * network reach, mirroring the MCP/A2A guidance); pass [bearerToken] to require
         * `Authorization: Bearer …` on every request.
         */
        fun from(
            handler: NlWebAskHandler,
            port: Int = 0,
            bearerToken: String? = null,
            maxRequestBytes: Int = DEFAULT_MAX_REQUEST_BYTES,
        ): NlWebServer = NlWebServer(handler, port, bearerToken, maxRequestBytes)

        const val DEFAULT_MAX_REQUEST_BYTES: Int = 1 shl 20 // 1 MiB

        private const val HTTP_OK = 200
        private const val HTTP_BAD_REQUEST = 400
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_METHOD_NOT_ALLOWED = 405
        private const val HTTP_SERVER_ERROR = 500
    }
}

private fun parseMode(raw: String?): NlWebMode = when (raw?.lowercase()) {
    "summarize" -> NlWebMode.SUMMARIZE
    "generate" -> NlWebMode.GENERATE
    else -> NlWebMode.LIST
}

/** Serialize an [NlWebSearchResult] to the NLWeb `/ask` response shape (#4542). Pure + internal for tests. */
internal fun renderAskResponse(result: NlWebSearchResult): String {
    val queryId = result.queryId ?: UUID.randomUUID().toString()
    val resultsJson = result.results.joinToString(",") { renderResult(it) }
    val summaryField = result.answer?.takeIf { it.isNotBlank() }?.let { ""","summary":${it.toJsonString()}""" } ?: ""
    return """{"query_id":${queryId.toJsonString()},"results":[$resultsJson]$summaryField}"""
}

private fun renderResult(r: NlWebResult): String {
    val fields = buildList {
        add(""""url":${r.url.toJsonString()}""")
        r.name?.let { add(""""name":${it.toJsonString()}""") }
        r.site?.let { add(""""site":${it.toJsonString()}""") }
        r.score?.let { add(""""score":$it""") }
        r.description?.let { add(""""description":${it.toJsonString()}""") }
        r.schemaType?.let { add(""""schema_object":{"@type":${it.toJsonString()}}""") }
    }
    return "{${fields.joinToString(",")}}"
}
