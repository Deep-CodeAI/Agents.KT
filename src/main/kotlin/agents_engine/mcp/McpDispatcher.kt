package agents_engine.mcp

import agents_engine.core.Agent
import agents_engine.core.Decision
import agents_engine.core.Skill
import agents_engine.generation.toLlmInput

/**
 * `agents_engine/mcp/McpDispatcher.kt` — the transport-agnostic JSON-RPC protocol core of MCP
 * server-side hosting (#2795), extracted out of the [McpServer] god class. Owns the `when(method)`
 * routing and every per-method business handler (`initialize` / `ping` / `tools|prompts|resources
 * .list` / `tools/call` / `prompts/get` / `resources/read`), operating purely on `Map<*, *>` →
 * `String` JSON-RPC envelopes. Knows nothing about HTTP or stdio: both transports drive it.
 *
 * - HTTP ([McpServer]) parses the body for its own framing (202 for notifications, the
 *   `Mcp-Session-Id` header on `initialize`) and then calls [dispatchRequest].
 * - stdio ([McpStdioServer]) feeds whole lines to [dispatchEnvelope], which parses, drops
 *   notifications (returns null), and otherwise dispatches.
 *
 * Incoming `tools/call` requests are policy-gated (per-principal [isToolAllowed]) and pass through
 * the source agent's `onBeforeToolCall` decision chain before skill execution.
 */
internal class McpDispatcher(
    val agent: Agent<*, *>,
    private val exposedSkills: List<ExposedSkill>,
    private val registeredPrompts: List<RegisteredPrompt>,
    private val registeredResources: List<RegisteredResource>,
    private val toolPolicy: (ClientPrincipal, String) -> Boolean,
) {
    val sessionId: String = java.util.UUID.randomUUID().toString()

    fun snapshotFor(principal: ClientPrincipal): McpServerInfo {
        val allowedTools = exposedSkills.filter { isToolAllowed(principal, it.skill.name) }
        return McpServerInfo(
            name = SERVER_NAME,
            version = SERVER_VERSION,
            protocolVersion = MCP_PROTOCOL_VERSION,
            capabilities = McpCapabilities(
                tools = allowedTools
                    .takeIf { it.isNotEmpty() }
                    ?.let { McpToolsCapability(listChanged = false) },
                prompts = registeredPrompts
                    .takeIf { it.isNotEmpty() }
                    ?.let { McpPromptsCapability(listChanged = false) },
                resources = registeredResources
                    .takeIf { it.isNotEmpty() }
                    ?.let { McpResourcesCapability(listChanged = false, subscribe = false) },
            ),
            tools = allowedTools
                .takeIf { it.isNotEmpty() }
                ?.map { it.toMcpToolInfo() },
            prompts = registeredPrompts
                .takeIf { it.isNotEmpty() }
                ?.map { it.toMcpPromptInfo() },
            resources = registeredResources
                .takeIf { it.isNotEmpty() }
                ?.map { it.toMcpResourceInfo() },
        )
    }

    /**
     * Transport-agnostic envelope entry (used by stdio). Parses [bodyText], answers parse / invalid
     * errors as JSON-RPC error envelopes, drops notifications (returns null), and otherwise
     * dispatches as the trusted-local principal. Replaces the old `McpServer.dispatchJsonRpc` back
     * door that stdio had to reach through.
     */
    fun dispatchEnvelope(bodyText: String, principal: ClientPrincipal = ClientPrincipal.TrustedLocal): String? = try {
        val request = JsonRpc.parseEnvelope(bodyText)
            ?: return JsonRpc.encodeError(null, JsonRpcErrorCode.PARSE_ERROR, "Parse error")
        if (request[JsonRpcWire.KEY_METHOD] !is String) {
            return JsonRpc.encodeError(null, JsonRpcErrorCode.INVALID_REQUEST, "Missing method")
        }
        if (JsonRpc.isNotification(request)) return null
        dispatchRequest(request, principal)
    } catch (e: Exception) {
        JsonRpc.encodeError(null, JsonRpcErrorCode.INTERNAL_ERROR, e.message ?: e.toString())
    }

    fun dispatchRequest(request: Map<*, *>, principal: ClientPrincipal): String {
        val method = request["method"] as? String
            ?: return jsonRpcError(request["id"], JsonRpcErrorCode.INVALID_REQUEST, "Missing method")
        val id = request["id"]
        return when (method) {
            "initialize" -> handleInitialize(id, request, principal)
            "ping" -> jsonRpcResult(id, emptyMap<String, Any?>())
            "tools/list" -> jsonRpcResult(id, mapOf(
                "tools" to exposedSkills
                    .filter { isToolAllowed(principal, it.skill.name) }
                    .map { it.toMcpDescriptor() },
                "nextCursor" to null,
            ))
            "tools/call" -> handleToolCall(id, request, principal)
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
            else -> jsonRpcError(id, JsonRpcErrorCode.METHOD_NOT_FOUND, "Method not found: $method")
        }
    }

    private fun handleInitialize(id: Any?, request: Map<*, *>, principal: ClientPrincipal): String {
        val params = request["params"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
        val requested = params["protocolVersion"] as? String
        if (requested != null && requested != MCP_PROTOCOL_VERSION) {
            return jsonRpcError(
                id,
                JsonRpcErrorCode.INVALID_PARAMS,
                "Unsupported protocolVersion: \"$requested\". Server speaks: \"$MCP_PROTOCOL_VERSION\".",
            )
        }
        val capabilities = snapshotFor(principal).capabilities.toWireMap()
        return jsonRpcResult(id, mapOf(
            "protocolVersion" to MCP_PROTOCOL_VERSION,
            "capabilities" to capabilities,
            "serverInfo" to mapOf("name" to SERVER_NAME, "version" to SERVER_VERSION),
        ))
    }

    private fun handlePromptGet(id: Any?, request: Map<*, *>): String {
        val params = request["params"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
        val name = params["name"] as? String
            ?: return jsonRpcError(id, JsonRpcErrorCode.INVALID_PARAMS, "Missing prompt name")
        val prompt = registeredPrompts.firstOrNull { it.name == name }
            ?: return jsonRpcError(id, JsonRpcErrorCode.METHOD_NOT_FOUND, "Unknown prompt: $name")
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
            jsonRpcError(
                id,
                JsonRpcErrorCode.INTERNAL_ERROR,
                "Prompt '$name' rendering failed: ${e.message ?: e.toString()}",
            )
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

    private fun RegisteredPrompt.toMcpPromptInfo(): McpPromptInfo =
        McpPromptInfo(name = name, description = description, arguments = arguments)

    private fun handleResourceRead(id: Any?, request: Map<*, *>): String {
        val params = request["params"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
        val uri = params["uri"] as? String
            ?: return jsonRpcError(id, JsonRpcErrorCode.INVALID_PARAMS, "Missing resource uri")
        val resource = registeredResources.firstOrNull { it.uri == uri }
            ?: return jsonRpcError(id, JsonRpcErrorCode.METHOD_NOT_FOUND, "Unknown resource uri: $uri")
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
            jsonRpcError(
                id,
                JsonRpcErrorCode.INTERNAL_ERROR,
                "Resource '$uri' read failed: ${e.message ?: e.toString()}",
            )
        }
    }

    private fun RegisteredResource.toMcpDescriptor(): Map<String, Any?> = buildMap {
        put("uri", uri)
        put("name", name)
        description?.let { put("description", it) }
        mimeType?.let { put("mimeType", it) }
    }

    private fun RegisteredResource.toMcpResourceInfo(): McpResourceInfo =
        McpResourceInfo(uri = uri, name = name, description = description, mimeType = mimeType)

    private fun handleToolCall(id: Any?, request: Map<*, *>, principal: ClientPrincipal): String {
        val params = request["params"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
        val name = params["name"] as? String
            ?: return jsonRpcError(id, JsonRpcErrorCode.INVALID_PARAMS, "Missing tool name")
        if (!isToolAllowed(principal, name)) {
            return jsonRpcError(id, JsonRpcErrorCode.METHOD_NOT_FOUND, "Method not found")
        }
        val exposed = exposedSkills.firstOrNull { it.skill.name == name }
            ?: return jsonRpcError(id, JsonRpcErrorCode.METHOD_NOT_FOUND, "Unknown tool: $name")
        @Suppress("UNCHECKED_CAST")
        val args = (params["arguments"] as? Map<String, Any?>) ?: emptyMap()
        return try {
            val effectiveArgs = when (val decision = agent.decideBeforeToolCall(name, args)) {
                Decision.Proceed -> args
                is Decision.ProceedWith -> decision.replacement
                is Decision.Deny -> return jsonRpcResult(id, mcpToolResult(
                    text = "ERROR: Tool '$name' denied by policy: ${decision.reason}",
                    isError = true,
                ))
                is Decision.Substitute<*> -> return jsonRpcResult(id, mcpToolResult(
                    text = decision.result?.toString() ?: "",
                    isError = false,
                ))
            }
            val input = exposed.deserializeInput(effectiveArgs)
            @Suppress("UNCHECKED_CAST")
            val output = (exposed.skill as Skill<Any?, Any?>).execute(input)
            // #2483 — route through `toLlmInput` so `@Generable` outputs render
            // as JSON instead of the Kotlin data-class debug form
            // (`SearchPayload(text=Hello, source=wiki)`). String / Number /
            // Boolean stay clean; non-`@Generable` types still fall back to
            // `.toString()` (documented limitation — register a `@Generable`
            // output type for typed MCP boundaries).
            jsonRpcResult(id, mcpToolResult(toLlmInput(output), isError = false))
        } catch (e: Exception) {
            jsonRpcResult(id, mcpToolResult(e.message ?: e.toString(), isError = true))
        }
    }

    private fun mcpToolResult(text: String, isError: Boolean): Map<String, Any?> =
        mapOf(
            "content" to listOf(mapOf("type" to "text", "text" to text)),
            "isError" to isError,
        )

    // #2796 — thin instance-level wrappers around the shared envelope builders
    // in [JsonRpc]. Keep the method names so the dispatcher body reads as
    // before; the wire shape now flows through one source of truth.
    private fun jsonRpcResult(id: Any?, result: Any?): String =
        JsonRpc.encodeResult(id, result)

    private fun jsonRpcError(id: Any?, code: Int, message: String): String =
        JsonRpc.encodeError(id, code, message)

    fun isToolAllowed(principal: ClientPrincipal, toolName: String): Boolean =
        runCatching { toolPolicy(principal, toolName) }.getOrDefault(false)

    companion object {
        private const val SERVER_NAME = "agents-kt-mcp-server"
        // #2806 — was a hardcoded "0.1.3" that drifted from the project version;
        // now flows through BuildInfo so the JAR's Implementation-Version is the
        // single source of truth.
        private val SERVER_VERSION: String = agents_engine.internal.BuildInfo.version

        /**
         * #2795 — resolve a configured [McpExposeBuilder] into a dispatcher: validate at least one
         * exposure exists and that each exposed skill is non-agentic, then snapshot the resolved
         * [ExposedSkill]s. Shared by both [McpServer.from] (HTTP) and [McpStdioServer.from] (stdio)
         * so the two transports build identical protocol cores.
         */
        internal fun build(agent: Agent<*, *>, builder: McpExposeBuilder): McpDispatcher {
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
                    "Skill \"$name\" is agentic — McpServer only exposes non-agentic skills " +
                        "(implementedBy { }) in this slice."
                }
                ExposedSkill.of(skill)
            }
            return McpDispatcher(
                agent = agent,
                exposedSkills = exposed,
                registeredPrompts = builder.prompts,
                registeredResources = builder.resources,
                toolPolicy = builder.toolPolicy,
            )
        }

        internal fun from(agent: Agent<*, *>, block: McpExposeBuilder.() -> Unit): McpDispatcher =
            build(agent, McpExposeBuilder().apply(block))
    }
}

internal fun McpCapabilities.toWireMap(): Map<String, Any?> = buildMap {
    tools?.let { put("tools", mapOf("listChanged" to it.listChanged)) }
    prompts?.let { put("prompts", mapOf("listChanged" to it.listChanged)) }
    resources?.let {
        put("resources", mapOf("listChanged" to it.listChanged, "subscribe" to it.subscribe))
    }
}
