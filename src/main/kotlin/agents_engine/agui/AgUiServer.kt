package agents_engine.agui

import agents_engine.core.Agent
import agents_engine.generation.LenientJsonParser
import agents_engine.internal.toJsonString
import agents_engine.runtime.events.session
import agents_engine.x402.X402PaymentGate
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import java.io.OutputStream
import java.net.InetSocketAddress
import java.util.UUID
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking

/**
 * `agents_engine/agui/AgUiServer.kt` — #4523 (PRD §12.7). Serves an [Agent] over the
 * [AG-UI](https://github.com/ag-ui-protocol/ag-ui) protocol — the agent↔frontend layer (MCP = agent↔tools,
 * A2A = agent↔agent, AG-UI = agent↔user). The only interop surface that reaches an end-user UI (e.g. a
 * CopilotKit React chat) without us building a frontend.
 *
 * **Not a descriptor exporter — a runtime streaming surface.** A single `POST` of an AG-UI `RunAgentInput`
 * (`{threadId, runId, state, messages, tools, context}`) returns an **SSE stream of typed events**. This is a
 * direct bridge over the typed streaming [agents_engine.runtime.events.AgentSession]: the new user turn is the
 * last `user` message's content (the agent input), and each [agents_engine.runtime.events.AgentEvent] is
 * mapped to AG-UI events by [AgUiEventBridge], wrapped in the `RUN_STARTED … RUN_FINISHED` envelope.
 *
 * Same `from(agent)` shape, loopback-only posture, and threat model as `McpServer` / `A2AServer` /
 * `NlWebServer`: binds `127.0.0.1`, optional bearer auth, front with a TLS gateway for any network reach.
 * Hand-rolled over the JDK [HttpServer] — no AG-UI SDK (the community JVM SDKs are client-side only).
 *
 * Surfaces the lifecycle/text/tool/step event families plus REASONING (live model thinking — see
 * [AgUiEventBridge]); STATE events and client-tool round-trips (the next `POST` re-sends history) are follow-ups.
 */
class AgUiServer private constructor(
    private val agent: Agent<*, *>,
    private val portRequest: Int,
    private val bearerToken: String?,
    private val maxRequestBytes: Int,
    private val payment: X402PaymentGate? = null,
) {
    private var http: HttpServer? = null

    val url: String
        get() = http?.let { "http://localhost:${it.address.port}/agent" } ?: error("AgUiServer not started")

    fun start(): AgUiServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", portRequest), 0)
        val handler = HttpHandler { exchange -> handle(exchange) }
        server.createContext("/agent", payment?.gate(handler) ?: handler)
        server.executor = null
        server.start()
        http = server
        return this
    }

    fun stop() {
        http?.stop(0)
        http = null
    }

    private fun handle(exchange: HttpExchange) {
        try {
            if (!authorized(exchange)) {
                respondJson(exchange, HTTP_UNAUTHORIZED, """{"error":"Unauthorized"}""")
                return
            }
            if (exchange.requestMethod != "POST") {
                respondJson(exchange, HTTP_METHOD_NOT_ALLOWED, """{"error":"use POST"}""")
                return
            }
            val body = exchange.requestBody.use { it.readNBytes(maxRequestBytes) }.toString(Charsets.UTF_8)
            val root = LenientJsonParser.parse(body) as? Map<*, *>
                ?: return respondJson(exchange, HTTP_BAD_REQUEST, """{"error":"RunAgentInput is not a JSON object"}""")
            val input = lastUserMessage(root)
                ?: return respondJson(exchange, HTTP_BAD_REQUEST, """{"error":"no user message in messages[]"}""")
            val threadId = root["threadId"] as? String ?: UUID.randomUUID().toString()
            val runId = root["runId"] as? String ?: UUID.randomUUID().toString()
            streamRun(exchange, input, threadId, runId)
        } catch (e: Exception) {
            // Pre-stream failure (couldn't even read the request) — a normal error response.
            runCatching {
                respondJson(exchange, HTTP_SERVER_ERROR, """{"error":${(e.message ?: e.toString()).toJsonString()}}""")
            }
        } finally {
            exchange.close()
        }
    }

    private fun streamRun(exchange: HttpExchange, input: String, threadId: String, runId: String) {
        exchange.responseHeaders.add("Content-Type", "text/event-stream")
        exchange.responseHeaders.add("Cache-Control", "no-cache")
        exchange.sendResponseHeaders(HTTP_OK, 0) // 0 => chunked, open-ended stream
        val bridge = AgUiEventBridge(threadId, runId)
        exchange.responseBody.use { out ->
            writeEvent(out, bridge.runStarted())
            try {
                runBlocking {
                    @Suppress("UNCHECKED_CAST")
                    (agent as Agent<Any?, Any?>).session(input).events.collect { event ->
                        bridge.onEvent(event).forEach { writeEvent(out, it) }
                    }
                }
            } catch (e: Exception) {
                // The session normally surfaces failure as an AgentEvent.Failed (-> RUN_ERROR); this is a
                // backstop for an unexpected throw mid-stream so the client still gets a terminal event.
                writeEvent(out, bridge.runError(e.message ?: e.toString()))
            }
        }
    }

    private fun writeEvent(out: OutputStream, json: String) {
        out.write("data: $json\n\n".toByteArray(Charsets.UTF_8))
        out.flush()
    }

    private fun authorized(exchange: HttpExchange): Boolean {
        val expected = bearerToken ?: return true
        return exchange.requestHeaders.getFirst("Authorization") == "Bearer $expected"
    }

    private fun lastUserMessage(root: Map<*, *>): String? {
        val messages = root["messages"] as? List<*> ?: return null
        val lastUser = messages.filterIsInstance<Map<*, *>>().lastOrNull { it["role"] == "user" } ?: return null
        return lastUser["content"] as? String
    }

    private fun respondJson(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    companion object {
        /**
         * Expose [agent] as an AG-UI endpoint — same `from(agent)` shape as `McpServer` / `A2AServer` /
         * `NlWebServer`. POST a `RunAgentInput` to [url]; the last `user` message is the agent input and the
         * reply is an SSE stream of AG-UI events. Binds loopback-only (front with a gateway for network reach);
         * pass [bearerToken] to require `Authorization: Bearer …`.
         */
        fun from(
            agent: Agent<*, *>,
            port: Int = 0,
            bearerToken: String? = null,
            maxRequestBytes: Int = DEFAULT_MAX_REQUEST_BYTES,
            payment: X402PaymentGate? = null,
        ): AgUiServer = AgUiServer(agent, port, bearerToken, maxRequestBytes, payment)

        const val DEFAULT_MAX_REQUEST_BYTES: Int = 1 shl 20 // 1 MiB

        private const val HTTP_OK = 200
        private const val HTTP_BAD_REQUEST = 400
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_METHOD_NOT_ALLOWED = 405
        private const val HTTP_SERVER_ERROR = 500
    }
}
