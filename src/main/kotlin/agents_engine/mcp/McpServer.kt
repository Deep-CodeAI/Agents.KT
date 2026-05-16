package agents_engine.mcp

import agents_engine.core.Agent
import agents_engine.core.Skill
import agents_engine.generation.Generable
import agents_engine.generation.LenientJsonParser
import agents_engine.generation.constructFromMap
import agents_engine.generation.jsonSchema
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.reflect.KClass
import agents_engine.generation.hasGenerableAnnotation

/**
 * Exposes an [Agent]'s skills as MCP tools over Streamable HTTP.
 *
 * ```kotlin
 * val server = McpServer.from(coder) {
 *     port = 8080         // 0 = auto-assign
 *     expose("write-code")
 * }.start()
 * ```
 *
 * Scope (first cut):
 * - HTTP transport only (uses JDK [HttpServer])
 * - Non-agentic skills only (skills declared via `implementedBy { }`).
 *   Agentic skills require server-side LLM access — out of scope here.
 * - Skill `IN` must be `String` or a `@Generable` class. Other types rejected at [start].
 * - Skill output rendered as a single text content block (`toString()`).
 */
/**
 * #1796 — a server-side prompt registration. Mirrors the MCP wire shape
 * for prompts: a name, description, argument spec, and a render closure
 * that turns the call-time args map into the prompt text.
 */
internal data class RegisteredPrompt(
    val name: String,
    val description: String,
    val arguments: List<McpPromptArgument>,
    val render: (Map<String, Any?>) -> String,
)

/**
 * #1810 — a server-side resource registration. Mirrors the MCP wire
 * shape for resources: URI (the addressable handle), display name,
 * optional description and MIME type, and a `read` closure invoked on
 * `resources/read` to produce the resource's text content.
 */
internal data class RegisteredResource(
    val uri: String,
    val name: String,
    val description: String?,
    val mimeType: String?,
    val read: () -> String,
)

class McpServer private constructor(
    private val agent: Agent<*, *>,
    private val exposedSkills: List<ExposedSkill>,
    private val portRequest: Int,
    private val maxRequestBytes: Long = DEFAULT_MAX_REQUEST_BYTES,
    private val registeredPrompts: List<RegisteredPrompt> = emptyList(),
    private val registeredResources: List<RegisteredResource> = emptyList(),
) {
    private var http: HttpServer? = null
    private val sessionId: String = java.util.UUID.randomUUID().toString()

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

    private fun handle(exchange: HttpExchange) {
        try {
            if (exchange.requestMethod != "POST") {
                exchange.responseHeaders.add("Allow", "POST")
                respond(exchange, 405, """{"error":"Method Not Allowed — only POST is supported"}""")
                return
            }
            val ct = exchange.requestHeaders.getFirst("Content-Type")
            if (ct == null || !ct.startsWith("application/json")) {
                respond(exchange, 415, """{"error":"Unsupported Media Type — expected application/json"}""")
                return
            }
            // #851 — bound the request body before reading. Honors Content-Length when
            // present; falls back to a length-bounded read otherwise. Avoids OOM from
            // a same-host process posting a multi-GB body to the loopback server.
            val declaredLength = exchange.requestHeaders.getFirst("Content-Length")?.toLongOrNull()
            if (declaredLength != null && declaredLength > maxRequestBytes) {
                respond(exchange, 413, """{"error":"Payload Too Large — limit is $maxRequestBytes bytes"}""")
                return
            }
            val cap = maxRequestBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val bodyBytes = exchange.requestBody.use { it.readNBytes(cap + 1) }
            if (bodyBytes.size > cap) {
                respond(exchange, 413, """{"error":"Payload Too Large — limit is $maxRequestBytes bytes"}""")
                return
            }
            val bodyText = String(bodyBytes, Charsets.UTF_8)
            val request = LenientJsonParser.parse(bodyText) as? Map<*, *>
                ?: return respond(exchange, 400, "{}")
            val method = request["method"] as? String ?: return respond(exchange, 400, "{}")
            val id = request["id"]

            if (method.startsWith("notifications/")) { respond(exchange, 202, ""); return }

            val response: String = when (method) {
                "initialize" -> {
                    exchange.responseHeaders.add("Mcp-Session-Id", sessionId)
                    handleInitialize(id, request)
                }
                "ping" -> jsonRpcResult(id, emptyMap<String, Any?>())
                "tools/list" -> jsonRpcResult(id, mapOf(
                    "tools" to exposedSkills.map { it.toMcpDescriptor() },
                    "nextCursor" to null,
                ))
                "tools/call" -> handleToolCall(id, request)
                "prompts/list" -> jsonRpcResult(id, mapOf(
                    "prompts" to registeredPrompts.map { it.toMcpDescriptor() },
                    "nextCursor" to null,
                ))
                "prompts/get" -> handlePromptGet(id, request)
                "resources/list" -> jsonRpcResult(id, mapOf(
                    "resources" to registeredResources.map { it.toMcpDescriptor() },
                    "nextCursor" to null,
                ))
                "resources/read" -> handleResourceRead(id, request)
                else -> jsonRpcError(id, -32601, "Method not found: $method")
            }
            respond(exchange, 200, response)
        } catch (e: Exception) {
            respond(exchange, 500, """{"error":${McpJson.encode(e.message ?: e.toString())}}""")
        } finally {
            exchange.close()
        }
    }

    private fun handleInitialize(id: Any?, request: Map<*, *>): String {
        val params = request["params"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
        val requested = params["protocolVersion"] as? String
        if (requested != null && requested != MCP_PROTOCOL_VERSION) {
            return jsonRpcError(
                id,
                -32602,
                "Unsupported protocolVersion: \"$requested\". Server speaks: \"$MCP_PROTOCOL_VERSION\".",
            )
        }
        // #1796 / #1810: declare prompts and resources capabilities when registered.
        val capabilities = buildMap<String, Any?> {
            put("tools", mapOf("listChanged" to false))
            if (registeredPrompts.isNotEmpty()) {
                put("prompts", mapOf("listChanged" to false))
            }
            if (registeredResources.isNotEmpty()) {
                put("resources", mapOf("listChanged" to false, "subscribe" to false))
            }
        }
        return jsonRpcResult(id, mapOf(
            "protocolVersion" to MCP_PROTOCOL_VERSION,
            "capabilities" to capabilities,
            "serverInfo" to mapOf("name" to "agents-kt-mcp-server", "version" to "0.1.3"),
        ))
    }

    private fun handlePromptGet(id: Any?, request: Map<*, *>): String {
        val params = request["params"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
        val name = params["name"] as? String
            ?: return jsonRpcError(id, -32602, "Missing prompt name")
        val prompt = registeredPrompts.firstOrNull { it.name == name }
            ?: return jsonRpcError(id, -32601, "Unknown prompt: $name")
        @Suppress("UNCHECKED_CAST")
        val args = (params["arguments"] as? Map<String, Any?>) ?: emptyMap()
        return try {
            val rendered = prompt.render(args)
            jsonRpcResult(id, mapOf(
                "description" to prompt.description,
                "messages" to listOf(
                    mapOf(
                        "role" to "user",
                        "content" to mapOf("type" to "text", "text" to rendered),
                    ),
                ),
            ))
        } catch (e: Exception) {
            jsonRpcError(id, -32603, "Prompt '$name' rendering failed: ${e.message ?: e.toString()}")
        }
    }

    private fun RegisteredPrompt.toMcpDescriptor(): Map<String, Any?> = buildMap {
        put("name", name)
        put("description", description)
        if (arguments.isNotEmpty()) {
            put("arguments", arguments.map { arg ->
                buildMap<String, Any?> {
                    put("name", arg.name)
                    arg.description?.let { put("description", it) }
                    put("required", arg.required)
                }
            })
        }
    }

    private fun handleResourceRead(id: Any?, request: Map<*, *>): String {
        val params = request["params"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
        val uri = params["uri"] as? String
            ?: return jsonRpcError(id, -32602, "Missing resource uri")
        val resource = registeredResources.firstOrNull { it.uri == uri }
            ?: return jsonRpcError(id, -32601, "Unknown resource uri: $uri")
        return try {
            val content = resource.read()
            jsonRpcResult(id, mapOf(
                "contents" to listOf(
                    buildMap<String, Any?> {
                        put("uri", resource.uri)
                        resource.mimeType?.let { put("mimeType", it) }
                        put("text", content)
                    },
                ),
            ))
        } catch (e: Exception) {
            jsonRpcError(id, -32603, "Resource '$uri' read failed: ${e.message ?: e.toString()}")
        }
    }

    private fun RegisteredResource.toMcpDescriptor(): Map<String, Any?> = buildMap {
        put("uri", uri)
        put("name", name)
        description?.let { put("description", it) }
        mimeType?.let { put("mimeType", it) }
    }

    private fun handleToolCall(id: Any?, request: Map<*, *>): String {
        val params = request["params"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
        val name = params["name"] as? String
            ?: return jsonRpcError(id, -32602, "Missing tool name")
        val exposed = exposedSkills.firstOrNull { it.skill.name == name }
            ?: return jsonRpcError(id, -32601, "Unknown tool: $name")
        @Suppress("UNCHECKED_CAST")
        val args = (params["arguments"] as? Map<String, Any?>) ?: emptyMap()
        return try {
            val input = exposed.deserializeInput(args)
            @Suppress("UNCHECKED_CAST")
            val output = (exposed.skill as Skill<Any?, Any?>).execute(input)
            jsonRpcResult(id, mapOf(
                "content" to listOf(mapOf("type" to "text", "text" to (output?.toString() ?: ""))),
                "isError" to false,
            ))
        } catch (e: Exception) {
            jsonRpcResult(id, mapOf(
                "content" to listOf(mapOf("type" to "text", "text" to (e.message ?: e.toString()))),
                "isError" to true,
            ))
        }
    }

    private fun jsonRpcResult(id: Any?, result: Any?): String =
        """{"jsonrpc":"2.0","id":${McpJson.encode(id)},"result":${McpJson.encode(result)}}"""

    private fun jsonRpcError(id: Any?, code: Int, message: String): String =
        """{"jsonrpc":"2.0","id":${McpJson.encode(id)},"error":${McpJson.encode(mapOf("code" to code, "message" to message))}}"""

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
            require(
                builder.exposedNames.isNotEmpty() ||
                    builder.prompts.isNotEmpty() ||
                    builder.resources.isNotEmpty()
            ) {
                "McpServer requires at least one expose(skillName), prompt(...), or resource(...) registration."
            }
            val exposed = builder.exposedNames.map { name ->
                val skill = agent.skills[name]
                    ?: throw IllegalArgumentException(
                        "Skill \"$name\" not found on agent \"${agent.name}\". Available: ${agent.skills.keys}"
                    )
                require(!skill.isAgentic) {
                    "Skill \"$name\" is agentic — McpServer only exposes non-agentic skills (implementedBy { }) in this slice."
                }
                ExposedSkill.of(skill)
            }
            return McpServer(
                agent = agent,
                exposedSkills = exposed,
                portRequest = builder.port,
                maxRequestBytes = builder.maxRequestBytes,
                registeredPrompts = builder.prompts,
                registeredResources = builder.resources,
            )
        }
    }
}

class McpExposeBuilder internal constructor() {
    var port: Int = 0  // 0 = auto-assign
    /** Hard cap on inbound request body size. See #851. */
    var maxRequestBytes: Long = McpServer.DEFAULT_MAX_REQUEST_BYTES
    internal val exposedNames = mutableListOf<String>()
    internal val prompts = mutableListOf<RegisteredPrompt>()

    fun expose(skillName: String) { exposedNames += skillName }

    /**
     * #1796 — register a server-side prompt template. [render] is invoked
     * per `prompts/get` call with the client-supplied argument map; its
     * String output becomes the prompt text returned to the client.
     */
    fun prompt(
        name: String,
        description: String,
        arguments: List<McpPromptArgument> = emptyList(),
        render: (Map<String, Any?>) -> String,
    ) {
        require(prompts.none { it.name == name }) {
            "Prompt \"$name\" already registered on this McpServer."
        }
        prompts += RegisteredPrompt(name, description, arguments, render)
    }

    internal val resources = mutableListOf<RegisteredResource>()

    /**
     * #1810 — register a server-side resource. [content] is invoked
     * per `resources/read` call; its String return becomes the
     * resource's text content. Use a static return for static
     * resources; pass a closure that reads from disk/db/etc. for
     * dynamic content.
     */
    fun resource(
        uri: String,
        name: String,
        description: String? = null,
        mimeType: String? = null,
        content: () -> String,
    ) {
        require(resources.none { it.uri == uri }) {
            "Resource uri \"$uri\" already registered on this McpServer."
        }
        resources += RegisteredResource(uri, name, description, mimeType, content)
    }
}

internal class ExposedSkill private constructor(
    val skill: Skill<*, *>,
    private val inputBuilder: (Map<String, Any?>) -> Any?,
    private val schema: Map<String, Any?>,
) {
    fun toMcpDescriptor(): Map<String, Any?> = buildMap {
        put("name", skill.name)
        put("description", skill.description)
        put("inputSchema", schema)
    }

    fun deserializeInput(args: Map<String, Any?>): Any? = inputBuilder(args)

    companion object {
        fun of(skill: Skill<*, *>): ExposedSkill {
            val inType = skill.inType
            return when {
                inType == String::class -> ExposedSkill(
                    skill = skill,
                    inputBuilder = { args ->
                        args["input"] as? String
                            ?: error("tool '${skill.name}' expects {\"input\": string}; got: $args")
                    },
                    schema = mapOf(
                        "type" to "object",
                        "properties" to mapOf("input" to mapOf("type" to "string")),
                        "required" to listOf("input"),
                    ),
                )
                // #1718: cache-aware probe, reflection-free when KSP generated
                // the companion. Falls through to wrapped reflection otherwise.
                inType.hasGenerableAnnotation() -> ExposedSkill(
                    skill = skill,
                    inputBuilder = { args ->
                        @Suppress("UNCHECKED_CAST")
                        (inType as KClass<Any>).constructFromMap(args)
                            ?: error("tool '${skill.name}' could not deserialize @Generable ${inType.simpleName} from: $args")
                    },
                    schema = parseSchema(inType.jsonSchema()),
                )
                else -> throw IllegalArgumentException(
                    "Skill \"${skill.name}\" has unsupported IN type ${inType.simpleName}. " +
                        "McpServer only exposes skills whose IN is String or a @Generable class."
                )
            }
        }

        @Suppress("UNCHECKED_CAST")
        private fun parseSchema(json: String): Map<String, Any?> =
            (LenientJsonParser.parse(json) as? Map<String, Any?>)
                ?: mapOf("type" to "object")
    }
}
