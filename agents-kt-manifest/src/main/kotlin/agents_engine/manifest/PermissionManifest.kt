package agents_engine.manifest

import agents_engine.composition.branch.Branch
import agents_engine.composition.branch.BranchRoute
import agents_engine.composition.forum.Forum
import agents_engine.composition.loop.Loop
import agents_engine.composition.parallel.Parallel
import agents_engine.composition.pipeline.Pipeline
import agents_engine.core.Agent
import agents_engine.core.ToolPolicy
import agents_engine.mcp.McpCapabilities
import agents_engine.mcp.ClientPrincipal
import agents_engine.mcp.McpServer
import agents_engine.mcp.McpPromptInfo
import agents_engine.mcp.McpResourceInfo
import agents_engine.mcp.McpResourceTemplateInfo
import agents_engine.mcp.McpServerInfo
import agents_engine.mcp.McpToolInfo
import agents_engine.mcp.mcpClients
import agents_engine.model.BudgetConfig
import agents_engine.model.ModelConfig
import agents_engine.model.ModelProvider
import agents_engine.model.ToolDef
import java.io.File
import java.security.MessageDigest
import kotlin.reflect.KClass

const val AGENTS_KT_MANIFEST_VERSION: Int = 1

class PermissionManifestOptions {
    var includeProviderConfig: Boolean = true
    var includeBudgets: Boolean = true
    var includeMcp: Boolean = true
    var includeMemory: Boolean = true
    var includePolicy: Boolean = true
    var includeComposition: Boolean = true

    internal fun toMap(): Map<String, Any?> = sortedMapOf(
        "includeBudgets" to includeBudgets,
        "includeComposition" to includeComposition,
        "includeMcp" to includeMcp,
        "includeMemory" to includeMemory,
        "includePolicy" to includePolicy,
        "includeProviderConfig" to includeProviderConfig,
    )
}

class PermissionManifest private constructor(
    private val contentWithoutHash: Map<String, Any?>,
) {
    val sha256: String = sha256Hex(StableJson.encode(contentWithoutHash))
    private val content: Map<String, Any?> = linkedMapOf(
        "agentsKtManifestVersion" to AGENTS_KT_MANIFEST_VERSION,
        "manifestSha256" to sha256,
    ) + contentWithoutHash.filterKeys { it != "agentsKtManifestVersion" && it != "manifestSha256" }

    fun toMap(): Map<String, Any?> = content

    fun toJson(): String = StableJson.encode(content)

    fun toYaml(): String = StableYaml.encode(content)

    fun writeJson(file: File) {
        file.parentFile?.mkdirs()
        file.writeText(toJson() + "\n")
    }

    fun writeYaml(file: File) {
        file.parentFile?.mkdirs()
        file.writeText(toYaml() + "\n")
    }

    fun verifyAgainst(baseline: PermissionManifest): ManifestVerificationResult =
        ManifestVerifier.verify(current = this, baseline = baseline)

    companion object {
        internal fun create(contentWithoutHash: Map<String, Any?>): PermissionManifest =
            PermissionManifest(contentWithoutHash)

        fun fromJson(json: String): PermissionManifest {
            val parsed = ManifestJsonParser.parse(json) as? Map<*, *>
                ?: error("Permission manifest JSON must be an object")
            val normalized = parsed.entries.associate { (key, value) -> key.toString() to value }
            return PermissionManifest(
                normalized.filterKeys { it != "manifestSha256" },
            )
        }
    }
}

data class ManifestVerificationResult(
    val findings: List<ManifestFinding>,
) {
    val ok: Boolean get() = findings.isEmpty()
}

data class ManifestFinding(
    val code: String,
    val severity: String,
    val path: String,
    val message: String,
)

interface PermissionManifestProvider {
    fun permissionManifest(): PermissionManifest
}

fun Agent<*, *>.permissionManifest(block: PermissionManifestOptions.() -> Unit = {}): PermissionManifest =
    buildPermissionManifest(
        ManifestGraph(
            type = "agent",
            agents = listOf(this),
            composition = agentComposition(this),
        ),
        block,
    )

fun Pipeline<*, *>.permissionManifest(block: PermissionManifestOptions.() -> Unit = {}): PermissionManifest =
    buildPermissionManifest(
        ManifestGraph(
            type = "pipeline",
            agents = agents,
            composition = pipelineComposition(agents),
        ),
        block,
    )

fun Parallel<*, *>.permissionManifest(block: PermissionManifestOptions.() -> Unit = {}): PermissionManifest =
    buildPermissionManifest(
        ManifestGraph(
            type = "parallel",
            agents = agents,
            composition = parallelComposition(agents),
        ),
        block,
    )

fun Forum<*, *>.permissionManifest(block: PermissionManifestOptions.() -> Unit = {}): PermissionManifest =
    buildPermissionManifest(
        ManifestGraph(
            type = "forum",
            agents = agents,
            composition = forumComposition(agents),
        ),
        block,
    )

fun Loop<*, *>.permissionManifest(block: PermissionManifestOptions.() -> Unit = {}): PermissionManifest =
    buildPermissionManifest(
        ManifestGraph(
            type = "loop",
            agents = agents,
            composition = loopComposition(agents),
        ),
        block,
    )

fun Branch<*, *>.permissionManifest(block: PermissionManifestOptions.() -> Unit = {}): PermissionManifest =
    buildPermissionManifest(
        ManifestGraph(
            type = "branch",
            agents = agents,
            composition = branchComposition(this),
        ),
        block,
    )

fun McpServer.permissionManifest(
    principal: ClientPrincipal = ClientPrincipal.TrustedLocal,
    block: PermissionManifestOptions.() -> Unit = {},
): PermissionManifest =
    buildPermissionManifest(
        ManifestGraph(
            type = "mcp-server",
            agents = listOf(agent),
            composition = mcpServerComposition(agent),
            extra = linkedMapOf(
                "mcpServers" to listOf(snapshotFor(principal).toManifestMap()),
            ),
        ),
        block,
    )

private data class ManifestGraph(
    val type: String,
    val agents: List<Agent<*, *>>,
    val composition: Map<String, Any?>,
    val extra: Map<String, Any?> = emptyMap(),
)

private fun buildPermissionManifest(
    graph: ManifestGraph,
    block: PermissionManifestOptions.() -> Unit,
): PermissionManifest {
    val options = PermissionManifestOptions().apply(block)
    val distinctAgents = graph.agents.distinct().sortedBy { it.name }
    val root = linkedMapOf<String, Any?>(
        "agentsKtManifestVersion" to AGENTS_KT_MANIFEST_VERSION,
        "format" to "agents-kt.permission-manifest",
        "subject" to mapOf(
            "type" to graph.type,
            "agents" to distinctAgents.map { it.name },
        ),
        "options" to options.toMap(),
        "agents" to distinctAgents.map { it.toManifestMap(options) },
    )
    if (options.includeComposition) {
        root["composition"] = graph.composition
    }
    root.putAll(graph.extra)
    val manifest = PermissionManifest.create(root)
    graph.agents.distinct().forEach { it.attachManifestHash(manifest.sha256) }
    return manifest
}

private fun Agent<*, *>.toManifestMap(options: PermissionManifestOptions): Map<String, Any?> {
    val skills = skills.values.sortedBy { it.name }
    val tools = toolMap.values.sortedBy { it.name }
    return linkedMapOf<String, Any?>(
        "name" to name,
        "inputTypes" to skills.map { typeName(it.inType) }.distinct().sorted(),
        "outputType" to typeName(outType),
        "promptConfigured" to prompt.isNotBlank(),
        "provider" to modelConfig?.toManifestMap(options),
        "budget" to if (options.includeBudgets) budgetConfig.toManifestMap() else null,
        "skills" to skills.map { skill ->
            linkedMapOf<String, Any?>(
                "name" to skill.name,
                "description" to skill.description,
                "inputType" to typeName(skill.inType),
                "outputType" to typeName(skill.outType),
                "mode" to if (skill.isAgentic) "agentic" else "deterministic",
                "toolAllowlist" to skill.toolNames.orEmpty().sorted(),
                "usesMemory" to skill.useMemory,
                "knowledge" to skill.knowledgeTools()
                    .sortedBy { it.name }
                    .map {
                        linkedMapOf(
                            "name" to it.name,
                            "description" to it.description,
                        )
                    },
            )
        },
        "tools" to tools.map { it.toManifestMap(options) },
        "memory" to if (options.includeMemory) memoryManifest(skills) else null,
        "mcp" to if (options.includeMcp) mcpManifest(this) else null,
        "guardrails" to guardrailManifest(),
        "humanOversight" to humanOversightManifest(),
    ).filterValues { it != null }
}

private fun ToolDef.toManifestMap(options: PermissionManifestOptions): Map<String, Any?> =
    linkedMapOf<String, Any?>(
        "name" to name,
        "description" to description,
        "inputType" to (argsType?.let(::typeName) ?: "kotlin.collections.Map"),
        "untrustedOutput" to untrustedOutput,
        "risk" to risk.name.lowercase(),
        "declaresCapability" to (policy?.declaresAnyCapability ?: false),
        "policy" to if (options.includePolicy) policy?.toNormalizedManifestMap() else null,
    ).filterValues { it != null }

private fun ToolPolicy.toNormalizedManifestMap(): Map<String, Any?> =
    toManifestMap().normalizePolicyRisk()

@Suppress("UNCHECKED_CAST")
private fun Map<String, Any?>.normalizePolicyRisk(): Map<String, Any?> =
    entries.associate { (key, value) ->
        val normalized = when {
            key == "risk" && value != null -> value.toString().lowercase()
            value is Map<*, *> -> (value as Map<String, Any?>).normalizePolicyRisk()
            value is Iterable<*> -> value.map { item ->
                if (item is Map<*, *>) (item as Map<String, Any?>).normalizePolicyRisk() else item
            }
            else -> value
        }
        key to normalized
    }

private fun ModelConfig.toManifestMap(options: PermissionManifestOptions): Map<String, Any?> {
    if (!options.includeProviderConfig) {
        return linkedMapOf(
            "provider" to provider.manifestName(),
            "model" to name,
            "apiKeyPresent" to (apiKey != null),
        )
    }
    return linkedMapOf(
        "provider" to provider.manifestName(),
        "model" to name,
        "temperature" to temperature,
        "baseUrl" to when (provider) {
            ModelProvider.OLLAMA -> baseUrl
            ModelProvider.ANTHROPIC -> anthropicBaseUrl
            ModelProvider.OPENAI -> openAiBaseUrl
        },
        "host" to host,
        "port" to port,
        "maxTokens" to maxTokens,
        "apiKey" to if (apiKey == null) null else "masked",
        "apiKeyPresent" to (apiKey != null),
    )
}

private fun ModelProvider.manifestName(): String = when (this) {
    ModelProvider.OLLAMA -> "ollama"
    ModelProvider.ANTHROPIC -> "anthropic"
    ModelProvider.OPENAI -> "openai"
}

private fun BudgetConfig.toManifestMap(): Map<String, Any?> =
    linkedMapOf(
        "maxTurns" to maxTurns,
        "maxToolCalls" to maxToolCalls,
        "maxDurationMillis" to maxDuration.inWholeMilliseconds,
        "perToolTimeoutMillis" to perToolTimeout?.inWholeMilliseconds,
        "maxTokens" to maxTokens,
        "maxConsecutiveSameTool" to maxConsecutiveSameTool,
    )

private fun Agent<*, *>.memoryManifest(skills: List<agents_engine.core.Skill<*, *>>): Map<String, Any?> =
    linkedMapOf(
        "enabled" to (memoryBank != null),
        "skillOptIn" to skills.filter { it.useMemory }.map { it.name }.sorted(),
        "tools" to toolMap.keys.filter { it.startsWith("memory_") }.sorted(),
    )

private fun mcpManifest(agent: Agent<*, *>): Map<String, Any?> =
    linkedMapOf(
        "clients" to agent.mcpClients.mapNotNull { it.snapshot }.map { it.toManifestMap() }.sortedBy { it["name"].toString() },
    )

private fun McpServerInfo.toManifestMap(): Map<String, Any?> =
    linkedMapOf(
        "name" to name,
        "title" to title,
        "version" to version,
        "protocolVersion" to protocolVersion,
        "instructionsPresent" to (instructions != null),
        "capabilities" to capabilities.toManifestMap(),
        "tools" to tools?.map { it.toManifestMap() }?.sortedBy { it["name"].toString() },
        "resources" to resources?.map { it.toManifestMap() }?.sortedBy { it["uri"].toString() },
        "resourceTemplates" to resourceTemplates?.map { it.toManifestMap() }?.sortedBy { it["uriTemplate"].toString() },
        "prompts" to prompts?.map { it.toManifestMap() }?.sortedBy { it["name"].toString() },
    ).filterValues { it != null }

private fun McpCapabilities.toManifestMap(): Map<String, Any?> =
    linkedMapOf(
        "tools" to (tools?.let { mapOf("listChanged" to it.listChanged) }),
        "resources" to (resources?.let { mapOf("listChanged" to it.listChanged, "subscribe" to it.subscribe) }),
        "prompts" to (prompts?.let { mapOf("listChanged" to it.listChanged) }),
        "logging" to logging,
        "completions" to completions,
        "experimental" to experimental,
    ).filterValues { it != null }

private fun McpToolInfo.toManifestMap(): Map<String, Any?> =
    linkedMapOf(
        "name" to name,
        "title" to title,
        "description" to description,
        "inputSchema" to inputSchema,
        "outputSchema" to outputSchema,
        "annotations" to annotations?.let {
            linkedMapOf(
                "title" to it.title,
                "readOnlyHint" to it.readOnlyHint,
                "destructiveHint" to it.destructiveHint,
                "idempotentHint" to it.idempotentHint,
                "openWorldHint" to it.openWorldHint,
            ).filterValues { value -> value != null }
        },
    ).filterValues { it != null }

private fun McpResourceInfo.toManifestMap(): Map<String, Any?> =
    linkedMapOf(
        "uri" to uri,
        "name" to name,
        "title" to title,
        "description" to description,
        "mimeType" to mimeType,
        "size" to size,
    ).filterValues { it != null }

private fun McpResourceTemplateInfo.toManifestMap(): Map<String, Any?> =
    linkedMapOf(
        "uriTemplate" to uriTemplate,
        "name" to name,
        "title" to title,
        "description" to description,
        "mimeType" to mimeType,
    ).filterValues { it != null }

private fun McpPromptInfo.toManifestMap(): Map<String, Any?> =
    linkedMapOf(
        "name" to name,
        "title" to title,
        "description" to description,
        "arguments" to arguments.map {
            linkedMapOf(
                "name" to it.name,
                "description" to it.description,
                "required" to it.required,
            ).filterValues { value -> value != null }
        },
    ).filterValues { it != null }

private fun Agent<*, *>.guardrailManifest(): Map<String, Any?> =
    linkedMapOf(
        "beforeSkillInterceptors" to beforeSkillInterceptorCount,
        "beforeToolCallInterceptors" to beforeToolCallInterceptorCount,
        "beforeTurnInterceptors" to beforeTurnInterceptorCount,
        "onErrorHook" to (errorListener != null),
        "onBudgetThresholdHook" to (budgetThresholdListener != null),
        "onTokenUsageHooks" to tokenUsageListenerCount,
    )

private fun Agent<*, *>.humanOversightManifest(): Map<String, Any?> =
    linkedMapOf(
        "escalationToolAvailable" to ("escalate" in toolMap),
        "toolCallPolicyInterceptors" to beforeToolCallInterceptorCount,
    )

private fun agentComposition(agent: Agent<*, *>): Map<String, Any?> =
    linkedMapOf(
        "type" to "agent",
        "nodes" to listOf(agent.name),
        "edges" to emptyList<Map<String, Any?>>(),
    )

private fun mcpServerComposition(agent: Agent<*, *>): Map<String, Any?> =
    linkedMapOf(
        "type" to "mcp-server",
        "nodes" to listOf(agent.name),
        "sourceAgent" to agent.name,
    )

private fun pipelineComposition(agents: List<Agent<*, *>>): Map<String, Any?> =
    linkedMapOf(
        "type" to "pipeline",
        "nodes" to agents.map { it.name },
        "edges" to agents.zipWithNext().map { (from, to) ->
            linkedMapOf("from" to from.name, "to" to to.name, "type" to "then")
        },
    )

private fun parallelComposition(agents: List<Agent<*, *>>): Map<String, Any?> =
    linkedMapOf(
        "type" to "parallel",
        "nodes" to agents.map { it.name },
        "branches" to agents.mapIndexed { index, agent ->
            linkedMapOf("index" to index, "agent" to agent.name)
        },
    )

private fun forumComposition(agents: List<Agent<*, *>>): Map<String, Any?> =
    linkedMapOf(
        "type" to "forum",
        "participants" to agents.dropLast(1).map { it.name },
        "captain" to agents.lastOrNull()?.name,
        "nodes" to agents.map { it.name },
    )

private fun loopComposition(agents: List<Agent<*, *>>): Map<String, Any?> =
    linkedMapOf(
        "type" to "loop",
        "nodes" to agents.map { it.name },
        "body" to agents.map { it.name },
    )

private fun branchComposition(branch: Branch<*, *>): Map<String, Any?> =
    linkedMapOf(
        "type" to "branch",
        "source" to branch.source.name,
        "nodes" to branch.agents.map { it.name },
        "routes" to branch.routes.mapIndexed { index, route ->
            linkedMapOf(
                "index" to index,
                "match" to route.matchLabel(),
                "to" to route.routedAgentName,
                "agents" to route.targetAgents.map { it.name },
            ).filterValues { it != null }
        },
    )

private fun BranchRoute<*>.matchLabel(): String = when (this) {
    is BranchRoute.TypeRoute -> klass.qualifiedName ?: klass.simpleName ?: klass.toString()
    is BranchRoute.NullRoute -> "null"
    is BranchRoute.ElseRoute -> "else"
}

private fun typeName(type: KClass<*>): String =
    type.qualifiedName ?: type.simpleName ?: type.toString()

private object ManifestVerifier {
    fun verify(current: PermissionManifest, baseline: PermissionManifest): ManifestVerificationResult {
        val findings = mutableListOf<ManifestFinding>()
        val currentTools = current.toolsByName()
        val baselineTools = baseline.toolsByName()

        currentTools.forEach { (name, currentTool) ->
            val baselineTool = baselineTools[name]
            val currentRisk = currentTool.riskValue()
            if (baselineTool == null) {
                if (currentRisk >= RISK_HIGH) {
                    findings += ManifestFinding(
                        code = "tool.added.high-risk",
                        severity = "high",
                        path = "tools.$name",
                        message = "New high-risk tool \"$name\" was added.",
                    )
                }
                return@forEach
            }

            val baselineRisk = baselineTool.riskValue()
            if (currentRisk > baselineRisk && currentRisk >= RISK_HIGH) {
                findings += ManifestFinding(
                    code = "tool.risk.increased",
                    severity = "high",
                    path = "tools.$name.risk",
                    message = "Tool \"$name\" risk increased from ${baselineTool["risk"]} to ${currentTool["risk"]}.",
                )
            }

            if (currentTool.networkScore() > baselineTool.networkScore()) {
                findings += ManifestFinding(
                    code = "tool.network.widened",
                    severity = "high",
                    path = "tools.$name.policy.network",
                    message = "Tool \"$name\" gained wider network access.",
                )
            }

            if (currentTool.filesystemScore("write") > baselineTool.filesystemScore("write")) {
                findings += ManifestFinding(
                    code = "tool.filesystem.write.widened",
                    severity = "high",
                    path = "tools.$name.policy.filesystem.write",
                    message = "Tool \"$name\" gained wider filesystem write access.",
                )
            }

            if (currentTool.filesystemScore("read") > baselineTool.filesystemScore("read")) {
                findings += ManifestFinding(
                    code = "tool.filesystem.read.widened",
                    severity = "medium",
                    path = "tools.$name.policy.filesystem.read",
                    message = "Tool \"$name\" gained wider filesystem read access.",
                )
            }
        }

        return ManifestVerificationResult(findings)
    }

    private const val RISK_HIGH = 3

    @Suppress("UNCHECKED_CAST")
    private fun PermissionManifest.toolsByName(): Map<String, Map<String, Any?>> {
        val result = linkedMapOf<String, Map<String, Any?>>()
        val agents = toMap()["agents"] as? List<*> ?: return result
        agents.forEach { rawAgent ->
            val agent = rawAgent as? Map<*, *> ?: return@forEach
            val tools = agent["tools"] as? List<*> ?: return@forEach
            tools.forEach { rawTool ->
                val tool = rawTool as? Map<*, *> ?: return@forEach
                val name = tool["name"]?.toString() ?: return@forEach
                result.putIfAbsent(name, tool as Map<String, Any?>)
            }
        }
        return result
    }

    private fun Map<String, Any?>.riskValue(): Int =
        when (this["risk"]?.toString()?.lowercase()) {
            "critical" -> 4
            "high" -> 3
            "medium" -> 2
            "low" -> 1
            else -> 0
        }

    private fun Map<String, Any?>.networkScore(): Int {
        val network = policySection("network")
        return when (network["mode"]?.toString()?.lowercase()) {
            "allowall" -> 2
            "hosts" -> if (stringList(network["hosts"]).isNotEmpty()) 1 else 0
            else -> 0
        }
    }

    private fun Map<String, Any?>.filesystemScore(side: String): Int {
        val access = policySection("filesystem").mapValue(side)
        return when (access["mode"]?.toString()?.lowercase()) {
            "globs" -> if (stringList(access["globs"]).isNotEmpty()) 1 else 0
            else -> 0
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.policySection(name: String): Map<String, Any?> =
        ((this["policy"] as? Map<*, *>)?.get(name) as? Map<String, Any?>) ?: emptyMap()

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.mapValue(name: String): Map<String, Any?> =
        (this[name] as? Map<String, Any?>) ?: emptyMap()

    private fun stringList(value: Any?): List<String> =
        when (value) {
            is Iterable<*> -> value.map { it.toString() }
            is Array<*> -> value.map { it.toString() }
            null -> emptyList()
            else -> listOf(value.toString())
        }
}

private object StableJson {
    fun encode(value: Any?): String = when (value) {
        null -> "null"
        is Boolean, is Number -> value.toString()
        is String -> quote(value)
        is Map<*, *> -> value.entries
            .sortedBy { it.key.toString() }
            .joinToString(",", "{", "}") { (key, mapValue) ->
                "${quote(key.toString())}:${encode(mapValue)}"
            }
        is Iterable<*> -> value.joinToString(",", "[", "]") { encode(it) }
        is Array<*> -> value.joinToString(",", "[", "]") { encode(it) }
        else -> quote(value.toString())
    }

    private fun quote(value: String): String =
        buildString(value.length + 2) {
            append('"')
            value.forEach { ch ->
                when (ch) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\b' -> append("\\b")
                    '\u000C' -> append("\\f")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (ch < ' ') {
                        append("\\u${ch.code.toString(16).padStart(4, '0')}")
                    } else {
                        append(ch)
                    }
                }
            }
            append('"')
        }
}

private object ManifestJsonParser {
    fun parse(text: String): Any? = Parser(text).parse()

    private class Parser(private val text: String) {
        private var index: Int = 0

        fun parse(): Any? {
            val value = parseValue()
            skipWhitespace()
            require(index == text.length) { "Unexpected trailing JSON at offset $index" }
            return value
        }

        private fun parseValue(): Any? {
            skipWhitespace()
            require(index < text.length) { "Unexpected end of JSON" }
            return when (val ch = text[index]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> consumeLiteral("true", true)
                'f' -> consumeLiteral("false", false)
                'n' -> consumeLiteral("null", null)
                '-', in '0'..'9' -> parseNumber()
                else -> error("Unexpected JSON character '$ch' at offset $index")
            }
        }

        private fun parseObject(): Map<String, Any?> {
            expect('{')
            skipWhitespace()
            val out = linkedMapOf<String, Any?>()
            if (peek('}')) {
                expect('}')
                return out
            }
            while (true) {
                val key = parseString()
                skipWhitespace()
                expect(':')
                out[key] = parseValue()
                skipWhitespace()
                when {
                    peek(',') -> expect(',')
                    peek('}') -> {
                        expect('}')
                        return out
                    }
                    else -> error("Expected ',' or '}' at offset $index")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            expect('[')
            skipWhitespace()
            val out = mutableListOf<Any?>()
            if (peek(']')) {
                expect(']')
                return out
            }
            while (true) {
                out += parseValue()
                skipWhitespace()
                when {
                    peek(',') -> expect(',')
                    peek(']') -> {
                        expect(']')
                        return out
                    }
                    else -> error("Expected ',' or ']' at offset $index")
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            val out = StringBuilder()
            while (index < text.length) {
                val ch = text[index++]
                when (ch) {
                    '"' -> return out.toString()
                    '\\' -> {
                        require(index < text.length) { "Unterminated JSON escape" }
                        out.append(
                            when (val escaped = text[index++]) {
                                '"' -> '"'
                                '\\' -> '\\'
                                '/' -> '/'
                                'b' -> '\b'
                                'f' -> '\u000C'
                                'n' -> '\n'
                                'r' -> '\r'
                                't' -> '\t'
                                'u' -> parseUnicodeEscape()
                                else -> error("Invalid JSON escape '\\$escaped' at offset ${index - 1}")
                            },
                        )
                    }
                    else -> out.append(ch)
                }
            }
            error("Unterminated JSON string")
        }

        private fun parseUnicodeEscape(): Char {
            require(index + 4 <= text.length) { "Incomplete unicode escape at offset $index" }
            val hex = text.substring(index, index + 4)
            index += 4
            return hex.toInt(16).toChar()
        }

        private fun parseNumber(): Number {
            val start = index
            if (peek('-')) index++
            while (index < text.length && text[index].isDigit()) index++
            if (peek('.')) {
                index++
                while (index < text.length && text[index].isDigit()) index++
            }
            if (index < text.length && (text[index] == 'e' || text[index] == 'E')) {
                index++
                if (index < text.length && (text[index] == '+' || text[index] == '-')) index++
                while (index < text.length && text[index].isDigit()) index++
            }
            val raw = text.substring(start, index)
            return if (raw.any { it == '.' || it == 'e' || it == 'E' }) raw.toDouble() else raw.toLong()
        }

        private fun consumeLiteral(literal: String, value: Any?): Any? {
            require(text.startsWith(literal, index)) { "Expected $literal at offset $index" }
            index += literal.length
            return value
        }

        private fun skipWhitespace() {
            while (index < text.length && text[index].isWhitespace()) index++
        }

        private fun expect(ch: Char) {
            skipWhitespace()
            require(index < text.length && text[index] == ch) { "Expected '$ch' at offset $index" }
            index++
        }

        private fun peek(ch: Char): Boolean = index < text.length && text[index] == ch
    }
}

private object StableYaml {
    fun encode(value: Any?): String = buildString {
        appendMap(value as? Map<*, *> ?: emptyMap<Any?, Any?>(), 0)
    }.trimEnd()

    private fun StringBuilder.appendMap(map: Map<*, *>, indent: Int) {
        map.entries.sortedBy { it.key.toString() }.forEach { (key, value) ->
            append(" ".repeat(indent))
            append(key.toString())
            when (value) {
                is Map<*, *> -> {
                    if (value.isEmpty()) {
                        appendLine(": {}")
                    } else {
                        appendLine(":")
                        appendMap(value, indent + 2)
                    }
                }
                is Iterable<*> -> appendList(key = null, value = value.toList(), indent = indent)
                is Array<*> -> appendList(key = null, value = value.toList(), indent = indent)
                else -> appendLine(": ${scalar(value)}")
            }
        }
    }

    private fun StringBuilder.appendList(key: String?, value: List<*>, indent: Int) {
        if (key != null) {
            append(" ".repeat(indent))
            append(key)
        }
        if (value.isEmpty()) {
            appendLine(": []")
            return
        }
        appendLine(":")
        value.forEach { item ->
            append(" ".repeat(indent + 2))
            append("-")
            when (item) {
                is Map<*, *> -> {
                    appendLine()
                    appendMap(item, indent + 4)
                }
                is Iterable<*> -> appendList(key = null, value = item.toList(), indent = indent + 2)
                else -> appendLine(" ${scalar(item)}")
            }
        }
    }

    private fun scalar(value: Any?): String = when (value) {
        null -> "null"
        is Boolean, is Number -> value.toString()
        else -> quote(value.toString())
    }

    private fun quote(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

private fun sha256Hex(text: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
