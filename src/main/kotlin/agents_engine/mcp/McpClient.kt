package agents_engine.mcp

import agents_engine.generation.LenientJsonParser
import agents_engine.model.ToolDef
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong

/**
 * `agents_engine/mcp/McpClient.kt` — the framework's MCP client.
 * Wraps an [McpTransport] (HTTP / TCP / stdio) and speaks JSON-RPC.
 * Lifecycle: construct via factory → `handshake()` initializes the
 * protocol + records server identity → `loadTools()` populates the
 * tool catalog. v0.5.0 adds `loadPrompts()` / `loadResources()` /
 * `loadResourceTemplates()` so the [snapshot] [McpServerInfo] (#1734)
 * carries the server's full surface for `toolSkills()` /
 * `promptSkills()` / `resourceSkills()` consumers. AutoCloseable.
 * See `src/main/resources/internals-agent/mcp/McpClient.md`
 * (#1837 / #1880).
 */
class McpClient internal constructor(private val transport: McpTransport) : AutoCloseable {

    private var toolDescriptors: List<McpToolDescriptor> = emptyList()
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

    /**
     * #1734 — pure-data view of everything we know about the connected server.
     * Populated after `handshake()` + `loadTools()` complete. The fields the
     * client doesn't currently fetch (resources, prompts, full capability
     * matrix) remain null/default; those land in follow-up issues as the
     * client gains new RPC calls. Consumers read off this snapshot rather
     * than the scattered `serverName` / `serverVersion` / private tools
     * accessors.
     */
    var snapshot: McpServerInfo? = null
        private set

    /** Server-reported capabilities map from the initialize handshake; raw shape so we can refine later without re-fetching. */
    private var rawServerCapabilities: Map<*, *> = emptyMap<Any?, Any?>()
    private var serverTitle: String? = null
    private var serverInstructions: String? = null

    /**
     * Mint a [ToolDef] for each tool the server exposes.
     *
     * When [prefix] is non-null, display names become `"$prefix.$wireName"`. The wire
     * name (sent on `tools/call`) is unchanged — only what the agent / LLM sees is
     * namespaced. Use this to register tools from multiple MCP servers in the same
     * agent without name collisions.
     */
    fun toolDefs(prefix: String? = null): List<ToolDef> = toolDescriptors.map { t ->
        ToolDef(
            name = if (prefix != null) "$prefix.${t.name}" else t.name,
            description = describeForLlm(t),
            // #2377 — forward the server's inputSchema as the tool's `parameters`
            // field. Without this the LLM only sees the schema embedded in the
            // description prose while the wire `parameters` falls back to the
            // permissive empty-object — conflicting signal.
            parametersSchemaJson = t.inputSchema?.let { McpJson.encode(it) },
            executor = { args -> call(t.name, args) },
        )
    }

    /**
     * #1948 — MCP-as-tools: expose every MCP-side tool as a first-class typed
     * [McpTool] handle. This is additive alongside [toolSkills], which remains
     * the prompt-style primary-skill adapter.
     */
    fun tools(prefix: String? = null): List<McpTool<Map<String, Any?>, String>> =
        toolDescriptors.map { descriptor ->
            McpTool.mapTool(
                client = this,
                descriptor = descriptor,
                displayName = if (prefix != null) "$prefix.${descriptor.name}" else descriptor.name,
                description = describeForLlm(descriptor),
            )
        }

    /**
     * #1795 — MCP-as-skills (1/3): expose every MCP-side tool as a [Skill]
     * usable as an agent primary skill (vs [toolDefs] which surfaces them
     * as auxiliary functions used inside another skill's agentic loop).
     *
     * Each returned skill has `inType = Map::class` (the tool's args map),
     * `outType = String::class` (MCP tool result rendered to text). Its
     * `implementedBy` invokes [call] with the wire-side tool name.
     *
     * Choice between the two:
     * - `toolDefs()` — the agent has its own skill driving an LLM that
     *   sometimes calls MCP tools as helpers. MCP capabilities are
     *   auxiliary.
     * - `toolSkills()` — the agent IS a thin wrapper over MCP. Each MCP
     *   capability is a primary entry point the agent can dispatch to.
     *
     * Both surfaces ship; consumers pick the shape that matches their
     * agent design.
     */
    fun toolSkills(prefix: String? = null): List<agents_engine.core.Skill<Map<String, Any?>, String>> = toolDescriptors.map { t ->
        val displayName = if (prefix != null) "$prefix.${t.name}" else t.name
        agents_engine.core.Skill<Map<String, Any?>, String>(
            name = displayName,
            description = describeForLlm(t),
            inType = Map::class,
            outType = String::class,
        ).also { skill ->
            skill.implementedBy { args -> call(t.name, args)?.toString() ?: "" }
        }
    }

    /**
     * #1796 — fetch prompt listings from the server (`prompts/list`).
     * Returns the raw `McpPromptInfo` records; for the Skill view use
     * [promptSkills].
     */
    fun listPrompts(): List<McpPromptInfo> {
        val result = post("prompts/list", emptyMap<String, Any?>())
        val resultMap = result as? Map<*, *> ?: return emptyList()
        val promptsList = resultMap["prompts"] as? List<*> ?: return emptyList()
        return promptsList.mapNotNull { raw ->
            val m = raw as? Map<*, *> ?: return@mapNotNull null
            val name = m["name"] as? String ?: return@mapNotNull null
            val description = m["description"] as? String
            val args = (m["arguments"] as? List<*>)?.mapNotNull { a ->
                val argMap = a as? Map<*, *> ?: return@mapNotNull null
                val argName = argMap["name"] as? String ?: return@mapNotNull null
                McpPromptArgument(
                    name = argName,
                    description = argMap["description"] as? String,
                    required = argMap["required"] as? Boolean ?: false,
                )
            } ?: emptyList()
            McpPromptInfo(
                name = name,
                title = m["title"] as? String,
                description = description,
                arguments = args,
            )
        }
    }

    /**
     * #1796 — render a server-side prompt template (`prompts/get`).
     * Joins all returned message text content blocks into a single
     * string. Consumers needing the structured message form should
     * use the raw RPC.
     */
    fun getPrompt(name: String, arguments: Map<String, Any?>): String {
        val result = post("prompts/get", mapOf("name" to name, "arguments" to arguments))
        val resultMap = result as? Map<*, *>
            ?: error("prompts/get returned non-object: $result")
        val messages = resultMap["messages"] as? List<*> ?: emptyList<Any?>()
        return messages.mapNotNull { msg ->
            val m = msg as? Map<*, *> ?: return@mapNotNull null
            val content = m["content"] as? Map<*, *> ?: return@mapNotNull null
            content["text"] as? String
        }.joinToString("\n")
    }

    /**
     * #1796 — MCP-as-skills (2/3): expose every server-side prompt as a
     * [Skill] usable as an agent primary skill. Each skill's
     * `implementedBy` invokes [getPrompt] with the call-time args and
     * returns the rendered text.
     */
    fun promptSkills(prefix: String? = null): List<agents_engine.core.Skill<Map<String, Any?>, String>> {
        return listPrompts().map { info ->
            val displayName = if (prefix != null) "$prefix.${info.name}" else info.name
            agents_engine.core.Skill<Map<String, Any?>, String>(
                name = displayName,
                description = info.description ?: "MCP prompt ${info.name}",
                inType = Map::class,
                outType = String::class,
            ).also { skill ->
                skill.implementedBy { args -> getPrompt(info.name, args) }
            }
        }
    }

    /**
     * #1810 — fetch resource listings from the server (`resources/list`).
     * Returns the raw `McpResourceInfo` records. For the Skill view, use
     * [resourceSkills].
     */
    fun listResources(): List<McpResourceInfo> {
        val result = post("resources/list", emptyMap<String, Any?>())
        val resultMap = result as? Map<*, *> ?: return emptyList()
        val resourcesList = resultMap["resources"] as? List<*> ?: return emptyList()
        return resourcesList.mapNotNull { raw ->
            val m = raw as? Map<*, *> ?: return@mapNotNull null
            val uri = m["uri"] as? String ?: return@mapNotNull null
            val name = m["name"] as? String ?: return@mapNotNull null
            McpResourceInfo(
                uri = uri,
                name = name,
                title = m["title"] as? String,
                description = m["description"] as? String,
                mimeType = m["mimeType"] as? String,
                size = (m["size"] as? Number)?.toLong(),
            )
        }
    }

    /**
     * #1810 — read a resource's content (`resources/read`). Joins all
     * returned text content blocks into a single string. Binary
     * (base64-encoded) resources are out of scope for this slice —
     * extend when needed.
     */
    fun readResource(uri: String): String {
        val result = post("resources/read", mapOf("uri" to uri))
        val resultMap = result as? Map<*, *>
            ?: error("resources/read returned non-object: $result")
        val contents = resultMap["contents"] as? List<*> ?: emptyList<Any?>()
        return contents.mapNotNull { c ->
            val m = c as? Map<*, *> ?: return@mapNotNull null
            m["text"] as? String
        }.joinToString("\n")
    }

    /**
     * #1810 — MCP-as-skills (3/3): expose every server-side resource as
     * a [Skill]. Skill `name` is the resource's display name (with
     * optional prefix); `implementedBy` invokes [readResource] with the
     * captured URI. Skill args are ignored — resources are addressed
     * by URI, not by call-time parameters.
     */
    fun resourceSkills(prefix: String? = null): List<agents_engine.core.Skill<Map<String, Any?>, String>> {
        return listResources().map { info ->
            val displayName = if (prefix != null) "$prefix.${info.name}" else info.name
            agents_engine.core.Skill<Map<String, Any?>, String>(
                name = displayName,
                description = info.description ?: "MCP resource ${info.uri}",
                inType = Map::class,
                outType = String::class,
            ).also { skill ->
                skill.implementedBy { _ -> readResource(info.uri) }
            }
        }
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
                serverTitle = info["title"] as? String
            }
            // #1734: capture capability matrix + instructions for the snapshot.
            rawServerCapabilities = result["capabilities"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
            serverInstructions = result["instructions"] as? String
        }

        transport.notify("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
    }

    private fun loadTools() {
        val result = post("tools/list", emptyMap<String, Any?>())
        val resultMap = result as? Map<*, *>
            ?: error("tools/list returned non-object: $result")
        val toolsList = resultMap["tools"] as? List<*>
            ?: error("tools/list result missing 'tools' array: $resultMap")
        toolDescriptors = toolsList.map { rawTool ->
            val m = rawTool as? Map<*, *>
                ?: error("tool descriptor is not an object: $rawTool")
            McpToolDescriptor(
                name = m["name"] as? String ?: error("tool descriptor missing 'name': $m"),
                description = m["description"] as? String ?: "",
                inputSchema = m["inputSchema"] as? Map<*, *>,
                title = m["title"] as? String,
                outputSchema = m["outputSchema"] as? Map<*, *>,
                annotations = (m["annotations"] as? Map<*, *>)?.let { ann ->
                    McpToolAnnotations(
                        title = ann["title"] as? String,
                        readOnlyHint = ann["readOnlyHint"] as? Boolean,
                        destructiveHint = ann["destructiveHint"] as? Boolean,
                        idempotentHint = ann["idempotentHint"] as? Boolean,
                        openWorldHint = ann["openWorldHint"] as? Boolean,
                    )
                },
            )
        }
        materializeSnapshot()
    }

    /**
     * #1734 — build [snapshot] from the data the handshake + loadTools steps
     * have already gathered. Fields we don't fetch yet (resources, prompts)
     * stay null even when the capability matrix says the server supports
     * them — the snapshot reflects what THIS client knows, not what the
     * server could in principle return. Follow-up issues add resources /
     * prompts fetching and populate those fields.
     */
    @Suppress("UNCHECKED_CAST")
    private fun materializeSnapshot() {
        val caps = McpCapabilities(
            tools = (rawServerCapabilities["tools"] as? Map<*, *>)?.let {
                McpToolsCapability(listChanged = it["listChanged"] as? Boolean ?: false)
            },
            resources = (rawServerCapabilities["resources"] as? Map<*, *>)?.let {
                McpResourcesCapability(
                    listChanged = it["listChanged"] as? Boolean ?: false,
                    subscribe = it["subscribe"] as? Boolean ?: false,
                )
            },
            prompts = (rawServerCapabilities["prompts"] as? Map<*, *>)?.let {
                McpPromptsCapability(listChanged = it["listChanged"] as? Boolean ?: false)
            },
            logging = rawServerCapabilities["logging"] != null,
            completions = rawServerCapabilities["completions"] != null,
            experimental = (rawServerCapabilities["experimental"] as? Map<String, Any?>) ?: emptyMap(),
        )
        val toolInfos = toolDescriptors.map { t ->
            McpToolInfo(
                name = t.name,
                title = t.title,
                description = t.description.ifEmpty { null },
                inputSchema = (t.inputSchema as? Map<String, Any?>) ?: emptyMap(),
                outputSchema = t.outputSchema as? Map<String, Any?>,
                annotations = t.annotations,
            )
        }
        snapshot = McpServerInfo(
            name = serverName ?: error("snapshot before handshake — serverName is null"),
            title = serverTitle,
            version = serverVersion ?: "",
            protocolVersion = serverProtocolVersion ?: "",
            instructions = serverInstructions,
            capabilities = caps,
            // Per spec the tools listing is meaningful only when the server declares the capability.
            // Default to the listing we just fetched regardless — McpServer-from-agent always declares tools.
            tools = toolInfos,
        )
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

        fun connect(url: String, auth: McpAuth = McpAuth.None): McpClient =
            McpClient(HttpMcpTransport(url, auth)).apply {
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
    val title: String? = null,
    val outputSchema: Map<*, *>? = null,
    val annotations: McpToolAnnotations? = null,
)
