package agents_engine.mcp

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

internal fun textBlock(text: String): Map<String, Any?> = mapOf("type" to "text", "text" to text)

class MockMcpServer internal constructor(
    private val server: HttpServer,
    private val protocol: MockMcpProtocol,
    private val useSse: Boolean,
    private val requiredBearer: String?,
) {
    private val seenSessionIds = CopyOnWriteArrayList<String>()
    private val seenBearerTokens = CopyOnWriteArrayList<String>()

    val url: String get() = "http://localhost:${server.address.port}/mcp"

    fun sessionIdsReceived(): List<String> = seenSessionIds.toList()

    /** Bearer tokens received in the `Authorization` header across all requests. */
    fun bearerTokensReceived(): List<String> = seenBearerTokens.toList()

    fun stop() { server.stop(0) }

    internal fun handle(exchange: HttpExchange) {
        try {
            exchange.requestHeaders.getFirst("Mcp-Session-Id")?.let { seenSessionIds.add(it) }
            val authHeader = exchange.requestHeaders.getFirst("Authorization")
            val presentedToken = authHeader?.removePrefix("Bearer ")?.trim()?.takeIf { authHeader.startsWith("Bearer ") }
            presentedToken?.let { seenBearerTokens.add(it) }
            if (requiredBearer != null && presentedToken != requiredBearer) {
                respond(exchange, 401, """{"error":"unauthorized: invalid or missing Bearer token"}""")
                return
            }
            val bodyText = exchange.requestBody.bufferedReader().use { it.readText() }
            val response = protocol.process(bodyText)
            if (response == null) {
                respond(exchange, 202, "")
                return
            }
            // The initialize response carries the session id back via a header.
            if (bodyText.contains("\"method\":\"initialize\"") && protocol.sessionId != null) {
                exchange.responseHeaders.add("Mcp-Session-Id", protocol.sessionId)
            }
            if (useSse) respondSse(exchange, response) else respond(exchange, 200, response)
        } catch (e: Exception) {
            respond(exchange, 500, """{"error":${McpJson.encode(e.message ?: e.toString())}}""")
        } finally {
            exchange.close()
        }
    }

    private fun respondSse(exchange: HttpExchange, jsonBody: String) {
        respond(exchange, 200, "event: message\ndata: $jsonBody\n\n", "text/event-stream")
    }

    private fun respond(exchange: HttpExchange, status: Int, body: String, contentType: String = "application/json") {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", contentType)
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
    }

    companion object {
        fun start(block: MockMcpServerBuilder.() -> Unit): MockMcpServer {
            val builder = MockMcpServerBuilder().apply(block)
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            val mock = MockMcpServer(
                server = server,
                protocol = builder.buildProtocol(),
                useSse = builder.useSseResponses,
                requiredBearer = builder.requiredBearerToken,
            )
            server.createContext("/mcp") { mock.handle(it) }
            server.executor = null
            server.start()
            return mock
        }
    }
}

internal data class JsonRpcError(val code: Int, val message: String)

internal data class MockTool(
    val name: String,
    val description: String,
    val inputSchema: String?,
    val handler: ((Map<String, Any?>) -> ToolResponse)?,
) {
    fun invoke(args: Map<String, Any?>): ToolResponse =
        handler?.invoke(args) ?: ToolResponse(content = listOf(textBlock("ok")), isError = false)
}

internal data class ToolResponse(val content: List<Map<String, Any?>>, val isError: Boolean)

class MockMcpServerBuilder internal constructor() {
    var sessionId: String? = null
    var useSseResponses: Boolean = false
    var protocolVersion: String = MCP_PROTOCOL_VERSION
    var serverName: String = "mock-mcp"
    var serverVersion: String = "0.0.1"
    internal var requiredBearerToken: String? = null
        private set
    private val tools = linkedMapOf<String, MockToolBuilder>()
    private val errors = mutableMapOf<String, JsonRpcError>()

    fun tool(name: String, block: MockToolBuilder.() -> Unit) {
        tools[name] = MockToolBuilder(name).apply(block)
    }

    fun jsonRpcError(forMethod: String, code: Int = -32603, message: String = "Internal error") {
        errors[forMethod] = JsonRpcError(code, message)
    }

    /** Require this exact Bearer token on every request. Missing or wrong → HTTP 401. */
    fun requireBearer(token: String) { requiredBearerToken = token }

    internal fun buildProtocol(): MockMcpProtocol = MockMcpProtocol(
        tools = tools.mapValues { (_, b) -> b.build() },
        errorByMethod = errors.toMap(),
        sessionId = sessionId,
        protocolVersion = protocolVersion,
        serverName = serverName,
        serverVersion = serverVersion,
    )
}

class MockToolBuilder internal constructor(private val name: String) {
    var description: String = ""
    var inputSchema: String? = null
    private var handler: ((Map<String, Any?>) -> ToolResponse)? = null

    fun respond(block: (Map<String, Any?>) -> List<Map<String, Any?>>) {
        handler = { args -> ToolResponse(content = block(args), isError = false) }
    }

    fun respondError(message: String) {
        handler = { _ -> ToolResponse(content = listOf(textBlock(message)), isError = true) }
    }

    internal fun build(): MockTool = MockTool(name, description, inputSchema, handler)
}
