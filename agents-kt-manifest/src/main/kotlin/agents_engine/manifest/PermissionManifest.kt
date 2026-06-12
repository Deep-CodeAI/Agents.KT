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
import agents_engine.generation.jsonSchema
import kotlin.reflect.KClass

// v2 (#3875): adds the top-level "schemas" section (JSON Schema per @Generable
// IN/OUT type, with per-schema sha256). v1 baselines verify with an info finding.
const val AGENTS_KT_MANIFEST_VERSION: Int = 2

class PermissionManifest private constructor(
    private val contentWithoutHash: Map<String, Any?>,
) {
    val sha256: String = sha256Hex(StableJson.encode(contentWithoutHash))
    private val content: Map<String, Any?> = linkedMapOf(
        // Preserve a loaded manifest's own version (#3875 — a v1 baseline must
        // still read as v1); fresh builds carry the current constant via root.
        "agentsKtManifestVersion" to (contentWithoutHash["agentsKtManifestVersion"] ?: AGENTS_KT_MANIFEST_VERSION),
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
        "schemas" to generableSchemas(distinctAgents),
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
            ModelProvider.DEEPSEEK -> deepSeekBaseUrl
            ModelProvider.KIMI -> kimiBaseUrl
            ModelProvider.OPENROUTER -> openRouterBaseUrl
            ModelProvider.PERPLEXITY -> perplexityBaseUrl
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
    ModelProvider.DEEPSEEK -> "deepseek"
    ModelProvider.KIMI -> "kimi"
    ModelProvider.OPENROUTER -> "openrouter"
    ModelProvider.PERPLEXITY -> "perplexity"
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

private fun sha256Hex(text: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

/**
 * #3875 — JSON Schema (via the KSP-aware cache-then-reflection probe) for
 * every distinct `@Generable` IN/OUT type in the agent graph, keyed by FQN
 * and sorted for determinism; each entry carries the parsed schema and its
 * sha256, so a schema change bumps `manifestHash` — reviewers see the
 * *types*, not just the names.
 */
private fun generableSchemas(agents: List<Agent<*, *>>): Map<String, Any?> {
    // Java reflection only — the CLI (and the no-reflect consumer profile)
    // runs without kotlin-reflect on the classpath (#1718 discipline).
    val types = agents.asSequence()
        .flatMap { agent -> agent.skills.values.asSequence().flatMap { sequenceOf(it.inType, it.outType) } + sequenceOf(agent.outType) }
        .filter { klass ->
            klass.java.annotations.any { it.annotationClass.java.name == "agents_engine.generation.Generable" }
        }
        .distinctBy { it.java.name }
        .sortedBy { it.java.name }
        .toList()
    return types.associate { klass ->
        val schemaJson = klass.jsonSchema()
        klass.java.name to linkedMapOf(
            // Raw JSON string (already deterministic from the generator) — the
            // sha256 is the stable comparison key for verifiers/baselines.
            "schema" to schemaJson,
            "sha256" to sha256Hex(schemaJson),
        )
    }
}
