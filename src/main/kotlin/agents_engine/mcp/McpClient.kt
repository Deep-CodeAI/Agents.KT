package agents_engine.mcp

import agents_engine.generation.LenientJsonParser
import agents_engine.model.ToolDef
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong

class McpClient internal constructor(private val transport: McpTransport) : AutoCloseable {

    private var tools: List<McpToolDescriptor> = emptyList()
    private val nextId = AtomicLong(2)

    /** Protocol version the server reported during `initialize`. Null until handshake completes. */
    var serverProtocolVersion: String? = null
        private set

    /** Server name reported during `initialize`. Null until handshake completes. */
    var serverName: String? = null
        private set

    /** Server version reported during `initialize`. Null until handshake completes. */
    var serverVersion: String? = null
        private set

    fun toolDefs(): List<ToolDef> = tools.map { t ->
        ToolDef(
            name = t.name,
            description = describeForLlm(t),
            executor = { args -> call(t.name, args) },
        )
    }

    fun call(toolName: String, args: Map<String, Any?>): Any? {
        val result = post("tools/call", mapOf("name" to toolName, "arguments" to args))
        val resultMap = result as? Map<*, *>
            ?: error("tools/call returned non-object: $result")
        val content = resultMap["content"] as? List<*>
            ?: error("tools/call result missing 'content' array: $resultMap")
        val isError = resultMap["isError"] as? Boolean ?: false
        val text = content.mapNotNull { block ->
            (block as? Map<*, *>)?.let { it["text"] as? String }
        }.joinToString("\n")
        if (isError) error("MCP tool '$toolName' failed: $text")
        return text
    }

    override fun close() { transport.close() }

    private fun handshake() {
        val initEnvelope = buildEnvelope(
            id = 1,
            method = "initialize",
            params = mapOf(
                "protocolVersion" to MCP_PROTOCOL_VERSION,
                "capabilities" to emptyMap<String, Any?>(),
                "clientInfo" to mapOf("name" to CLIENT_NAME, "version" to CLIENT_VERSION),
            ),
        )
        val initResp = parseResponse(transport.rpc(initEnvelope))
        require(initResp["error"] == null) { "MCP initialize failed: ${initResp["error"]}" }

        val result = initResp["result"] as? Map<*, *>
        if (result != null) {
            serverProtocolVersion = result["protocolVersion"] as? String
            (result["serverInfo"] as? Map<*, *>)?.let { info ->
                serverName = info["name"] as? String
                serverVersion = info["version"] as? String
            }
        }

        transport.notify("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
    }

    private fun loadTools() {
        val result = post("tools/list", emptyMap<String, Any?>())
        val resultMap = result as? Map<*, *>
            ?: error("tools/list returned non-object: $result")
        val toolsList = resultMap["tools"] as? List<*>
            ?: error("tools/list result missing 'tools' array: $resultMap")
        tools = toolsList.map { rawTool ->
            val m = rawTool as? Map<*, *>
                ?: error("tool descriptor is not an object: $rawTool")
            McpToolDescriptor(
                name = m["name"] as? String ?: error("tool descriptor missing 'name': $m"),
                description = m["description"] as? String ?: "",
                inputSchema = m["inputSchema"] as? Map<*, *>,
            )
        }
    }

    private fun post(method: String, params: Any?): Any? {
        val envelope = buildEnvelope(nextId.getAndIncrement(), method, params)
        val response = parseResponse(transport.rpc(envelope))
        response["error"]?.let { error("MCP $method failed: $it") }
        return response["result"]
    }

    private fun buildEnvelope(id: Long, method: String, params: Any?): String = buildString {
        append("""{"jsonrpc":"2.0","id":""")
        append(id)
        append(""","method":""")
        append(McpJson.encode(method))
        if (params != null) {
            append(""","params":""")
            append(McpJson.encode(params))
        }
        append("}")
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseResponse(payload: String): Map<String, Any?> =
        LenientJsonParser.parse(payload) as? Map<String, Any?>
            ?: error("MCP response was not a JSON object: $payload")

    private fun describeForLlm(t: McpToolDescriptor): String {
        if (t.inputSchema == null) return t.description
        return t.description + "\n\nInput JSON schema: " + McpJson.encode(t.inputSchema)
    }

    companion object {
        private const val CLIENT_NAME = "agents-kt"
        private const val CLIENT_VERSION = "0.1.3"

        fun connect(url: String): McpClient = McpClient(HttpMcpTransport(url)).apply {
            handshake(); loadTools()
        }

        fun connectTcp(host: String, port: Int): McpClient =
            McpClient(TcpMcpTransport(Socket(host, port))).apply {
                handshake(); loadTools()
            }

        fun connectStreams(input: InputStream, output: OutputStream): McpClient =
            McpClient(StdioMcpTransport.forStreams(input, output)).apply {
                handshake(); loadTools()
            }

        fun connectStdio(
            command: List<String>,
            env: Map<String, String> = emptyMap(),
            workingDir: java.io.File? = null,
            stderrSink: (String) -> Unit = {},
        ): McpClient = McpClient(StdioMcpTransport.forProcess(command, env, workingDir, stderrSink)).apply {
            handshake(); loadTools()
        }
    }
}

internal data class McpToolDescriptor(
    val name: String,
    val description: String,
    val inputSchema: Map<*, *>?,
)
