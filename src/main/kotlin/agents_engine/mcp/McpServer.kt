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
import kotlin.reflect.full.findAnnotation

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
class McpServer private constructor(
    private val agent: Agent<*, *>,
    private val exposedSkills: List<ExposedSkill>,
    private val portRequest: Int,
) {
    private var http: HttpServer? = null

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

    private fun handle(exchange: HttpExchange) {
        try {
            val bodyText = exchange.requestBody.bufferedReader().use { it.readText() }
            val request = LenientJsonParser.parse(bodyText) as? Map<*, *>
                ?: return respond(exchange, 400, "{}")
            val method = request["method"] as? String ?: return respond(exchange, 400, "{}")
            val id = request["id"]

            if (method.startsWith("notifications/")) { respond(exchange, 202, ""); return }

            val response: String = when (method) {
                "initialize" -> jsonRpcResult(id, mapOf(
                    "protocolVersion" to MCP_PROTOCOL_VERSION,
                    "capabilities" to mapOf("tools" to emptyMap<String, Any?>()),
                    "serverInfo" to mapOf("name" to "agents-kt-mcp-server", "version" to "0.1.3"),
                ))
                "tools/list" -> jsonRpcResult(id, mapOf("tools" to exposedSkills.map { it.toMcpDescriptor() }))
                "tools/call" -> handleToolCall(id, request)
                else -> jsonRpcError(id, -32601, "Method not found: $method")
            }
            respond(exchange, 200, response)
        } catch (e: Exception) {
            respond(exchange, 500, """{"error":${McpJson.encode(e.message ?: e.toString())}}""")
        } finally {
            exchange.close()
        }
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
        fun from(agent: Agent<*, *>, block: McpExposeBuilder.() -> Unit): McpServer {
            val builder = McpExposeBuilder().apply(block)
            require(builder.exposedNames.isNotEmpty()) {
                "McpServer requires at least one expose(skillName) call."
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
            return McpServer(agent, exposed, builder.port)
        }
    }
}

class McpExposeBuilder internal constructor() {
    var port: Int = 0  // 0 = auto-assign
    internal val exposedNames = mutableListOf<String>()

    fun expose(skillName: String) { exposedNames += skillName }
}

internal class ExposedSkill private constructor(
    val skill: Skill<*, *>,
    private val inputBuilder: (Map<String, Any?>) -> Any?,
    private val schema: Map<String, Any?>,
) {
    fun toMcpDescriptor(): Map<String, Any?> = buildMap {
        put("name", skill.name)
        put("description", skill.toLlmDescription())
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
                inType.findAnnotation<Generable>() != null -> ExposedSkill(
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
