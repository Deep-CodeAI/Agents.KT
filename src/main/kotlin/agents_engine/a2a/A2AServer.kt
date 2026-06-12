package agents_engine.a2a

import agents_engine.core.Agent
import agents_engine.generation.LenientJsonParser
import agents_engine.generation.codec
import agents_engine.generation.hasGenerableAnnotation
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.UUID
import kotlinx.coroutines.runBlocking

/**
 * `agents_engine/a2a/A2AServer.kt` — #3864. Exposes an `Agent<IN, OUT>`
 * over the A2A protocol (v0.2, JSON-RPC over HTTP), following the
 * [agents_engine.mcp.McpServer] precedent: JDK [HttpServer], loopback
 * bind, optional bearer auth for anything network-reachable.
 *
 * Surface (v1):
 * - `GET /.well-known/agent-card.json` — the AgentCard, with `@Generable`
 *   input schemas when available.
 * - `POST <basePath>` — JSON-RPC `message/send`: the message's first text
 *   part becomes the agent's typed input (raw string for `String` IN,
 *   lenient-JSON-decoded for `@Generable` IN); the reply is a completed
 *   A2A Task whose artifact carries the output (JSON-encoded property map
 *   for typed OUT, raw text otherwise).
 *
 * Out of scope in v1 (follow-ups on #3864): `message/stream` /
 * SSE streaming (composes with the #3866 session surface), `tasks/get` /
 * `tasks/cancel` (every task completes synchronously), push notifications.
 */
class A2AServer private constructor(
    private val agent: Agent<*, *>,
    private val portRequest: Int,
    private val basePath: String,
    private val bearerToken: String?,
) {
    private var http: HttpServer? = null

    val url: String
        get() = http?.let { "http://localhost:${it.address.port}$basePath" }
            ?: error("A2AServer not started")

    fun start(): A2AServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", portRequest), 0)
        server.createContext("/.well-known/agent-card.json") { exchange ->
            handleSafely(exchange) {
                respond(exchange, HTTP_OK, A2AJson.encode(agentCard(agent, url)))
            }
        }
        server.createContext(basePath) { exchange -> handleSafely(exchange) { handleRpc(exchange) } }
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
            respond(exchange, HTTP_SERVER_ERROR, """{"error":${A2AJson.encode(e.message ?: e.toString())}}""")
        } finally {
            exchange.close()
        }
    }

    private fun authorized(exchange: HttpExchange): Boolean {
        val expected = bearerToken ?: return true
        val header = exchange.requestHeaders.getFirst("Authorization") ?: return false
        return header == "Bearer $expected"
    }

    private fun handleRpc(exchange: HttpExchange) {
        val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
        val request = LenientJsonParser.parse(body) as? Map<*, *>
            ?: return respond(exchange, HTTP_BAD_REQUEST, rpcError(null, INVALID_REQUEST, "not a JSON-RPC request"))
        val id = request["id"]
        when (request["method"]) {
            "message/send" -> {
                val text = firstTextPart(request)
                    ?: return respond(exchange, HTTP_OK, rpcError(id, INVALID_PARAMS, "message has no text part"))
                val output = try {
                    @Suppress("UNCHECKED_CAST")
                    runBlocking { (agent as Agent<Any?, Any?>).invokeSuspend(coerceInput(text)) }
                } catch (e: Exception) {
                    return respond(exchange, HTTP_OK, rpcError(id, INTERNAL_ERROR, e.message ?: e.toString()))
                }
                respond(exchange, HTTP_OK, completedTask(id, output))
            }
            else -> respond(
                exchange,
                HTTP_OK,
                rpcError(id, METHOD_NOT_FOUND, "unsupported method (v1 supports message/send)"),
            )
        }
    }

    // Agent itself carries only OUT typing; IN typing lives on its skills.
    // A2A v1 requires one consistent IN type across skills (String when mixed
    // or absent — routing then happens on the raw text like a chat agent).
    private val inType: kotlin.reflect.KClass<*> =
        agent.skills.values.map { it.inType }.distinct().singleOrNull() ?: String::class

    private fun coerceInput(text: String): Any? = when {
        inType == String::class -> text
        inType.hasGenerableAnnotation() -> {
            val fields = LenientJsonParser.parse(text) as? Map<*, *>
                ?: error("expected a JSON object for @Generable ${inType.simpleName}; got: $text")
            inType.codec().decode(fields)
                ?: error("could not deserialize @Generable ${inType.simpleName} from: $text")
        }
        else -> error(
            "Agent \"${agent.name}\" has unsupported IN type ${inType.simpleName} for A2A. " +
                "Use String or a @Generable class.",
        )
    }

    private fun firstTextPart(request: Map<*, *>): String? {
        val params = request["params"] as? Map<*, *> ?: return null
        val message = params["message"] as? Map<*, *> ?: return null
        val parts = message["parts"] as? List<*> ?: return null
        return parts.filterIsInstance<Map<*, *>>().firstOrNull { it["kind"] == "text" || it["type"] == "text" }
            ?.get("text") as? String
    }

    private fun completedTask(id: Any?, output: Any?): String {
        val text = when {
            output == null -> ""
            output is String -> output
            A2AJson.isSimple(output::class) -> output.toString()
            else -> A2AJson.encodeTyped(output)
        }
        val task = linkedMapOf(
            "id" to UUID.randomUUID().toString(),
            "status" to mapOf("state" to "completed"),
            "artifacts" to listOf(
                mapOf("parts" to listOf(mapOf("kind" to "text", "text" to text))),
            ),
        )
        return """{"jsonrpc":"2.0","id":${A2AJson.encode(id)},"result":${A2AJson.encode(task)}}"""
    }

    private fun rpcError(id: Any?, code: Int, message: String): String =
        """{"jsonrpc":"2.0","id":${A2AJson.encode(id)},"error":{"code":$code,"message":${A2AJson.encode(message)}}}"""

    private fun respond(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    companion object {
        /**
         * Expose [agent] over A2A. Binds loopback-only (front with a gateway
         * for network reach, mirroring the MCP guidance); pass [bearerToken]
         * to require `Authorization: Bearer …` on every request.
         */
        fun from(
            agent: Agent<*, *>,
            port: Int = 0,
            basePath: String = "/a2a",
            bearerToken: String? = null,
        ): A2AServer = A2AServer(agent, port, basePath, bearerToken)

        private const val HTTP_OK = 200
        private const val HTTP_BAD_REQUEST = 400
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_SERVER_ERROR = 500
        private const val INVALID_REQUEST = -32600
        private const val METHOD_NOT_FOUND = -32601
        private const val INVALID_PARAMS = -32602
        private const val INTERNAL_ERROR = -32603
    }
}
